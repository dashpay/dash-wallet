/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.database.entity

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.base.Stopwatch
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.platform.PlatformService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import org.dashj.platform.dashpay.IdentityStatus
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dashpay.UsernameStatus
import org.dashj.platform.dpp.identity.Identity
import org.dashj.platform.dpp.toHex
import org.dashj.platform.dpp.util.Converters
import org.dashj.platform.sdk.KeyType
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri
import org.slf4j.Logger

enum class IdentityCreationState {
    NONE,   // this should always be the first value
    UPGRADING_WALLET,
    CREDIT_FUNDING_TX_CREATING,
    CREDIT_FUNDING_TX_SENDING,
    CREDIT_FUNDING_TX_SENT,
    CREDIT_FUNDING_TX_CONFIRMED,
    IDENTITY_REGISTERING,
    IDENTITY_REGISTERED,
    PREORDER_SECONDARY_REGISTERING,
    PREORDER_SECONDARY_REGISTERED,
    USERNAME_SECONDARY_REGISTERING,
    USERNAME_SECONDARY_REGISTERED,
    PREORDER_REGISTERING,
    PREORDER_REGISTERED,
    USERNAME_REGISTERING,
    USERNAME_REGISTERED,
    DASHPAY_PROFILE_CREATING,
    DASHPAY_PROFILE_CREATED,
    REQUESTED_NAME_CHECKING,
    REQUESTED_NAME_CHECKED,
    REQUESTED_NAME_LINK_SAVING,
    REQUESTED_NAME_LINK_SAVED,
    VOTING,
    DONE,
    DONE_AND_DISMISS // this should always be the last value
}

class BlockchainIdentityData(
    creationState: IdentityCreationState = IdentityCreationState.NONE,
    creationStateErrorMessage: String?,
    username: String?,
    usernameSecondary: String?,
    userId: String?,
    restoring: Boolean,
    creditFundingTxId: Sha256Hash? = null,
    usingInvite: Boolean = false,
    invite: InvitationLinkData? = null,
    verificationLink: String? = null,
    cancelledVerificationLink: Boolean? = null,
    usernameRequested: UsernameRequestStatus? = null,
    votingPeriodStart: Long? = null,
    // Extended properties specific to full identity data
    var identity: Identity? = null,
    var preorderSalt: ByteArray? = null,
    var preorderSaltSecondary: ByteArray? = null,
    var registrationStatus: IdentityStatus? = null,
    var usernameStatus: UsernameStatus? = null,
    var usernameSecondaryStatus: UsernameStatus? = null,
    var privacyMode: CoinJoinMode? = null,
    var creditBalance: Coin? = null,
    var activeKeyCount: Int? = null,
    var totalKeyCount: Int? = null,
    var keysCreated: Long? = null,
    var currentMainKeyIndex: Int? = null,
    var currentMainKeyType: KeyType? = null
) : BlockchainIdentityBaseData(
    creationState = creationState,
    creationStateErrorMessage = creationStateErrorMessage,
    username = username,
    usernameSecondary = usernameSecondary,
    userId = userId,
    restoring = restoring,
    creditFundingTxId = creditFundingTxId,
    usingInvite = usingInvite,
    invite = invite,
    requestedUsername = username, // Map username to requestedUsername
    verificationLink = verificationLink,
    cancelledVerificationLink = cancelledVerificationLink,
    usernameRequested = usernameRequested,
    votingPeriodStart = votingPeriodStart
) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(BlockchainIdentityData::class.java)
    }

    private var creditFundingTransactionCache: AssetLockTransaction? = null

    init {
        // debug only
        log.info("creation: {}", this)
    }

    fun findAssetLockTransaction(wallet: Wallet?): AssetLockTransaction? {
        if (super.creditFundingTxId == null) {
            val authExtension =
                wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
            val list = authExtension.assetLockTransactions
            list.sortBy { it.updateTime }
            return if (list.isEmpty()) null else list.first()
        }
        if (wallet != null) {
            val watch1 = Stopwatch.createStarted()
            creditFundingTransactionCache = wallet.getTransaction(super.creditFundingTxId)?.run {
                log.info("loading wallet.getTransaction: {}", watch1)
                val authExtension = wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
                val watch2 = Stopwatch.createStarted()
                val result = authExtension.getAssetLockTransaction(this)
                log.info("loading getAssetLockTransaction: {}", watch2)
                result
            }
        }
        return creditFundingTransactionCache
    }

    @Deprecated("use userId instead", ReplaceWith("userId"))
    fun getIdentity(wallet: Wallet?): String? = findAssetLockTransaction(wallet)?.identityId?.toStringBase58()

    fun getErrorMetadata() = super.creationStateErrorMessage?.let {
            val metadataIndex = it.indexOf("Metadata(")
            it.substring(metadataIndex)
    }

    // Note: These properties are now immutable in the parent class, 
    // so this method needs to be reconsidered or removed
     fun finishRestoration() {
         this.restoring = false
         this.creationStateErrorMessage = null
     }

    val activeUsername: String?
        get() = if (showSecondaryUsername) {
            usernameSecondary
        } else {
            username
        }

    val showSecondaryUsername: Boolean
        get() = votingInProgress && usernameSecondary != null && usernameSecondaryStatus == UsernameStatus.CONFIRMED

    val hasUsername: Boolean
        get() = creationComplete || showSecondaryUsername

    override fun toString(): String {
        return "BlockchainIdentityData($creationState, $username, $usernameStatus, $usernameSecondary, $usernameSecondaryStatus, $userId)"
    }
}

@Singleton
open class BlockchainIdentityConfig @Inject constructor(
    private val context: Context,
    walletDataProvider: WalletDataProvider,
    private val platformService: PlatformService
) : BaseConfig(
    context,
    PREFERENCES_NAME,
    walletDataProvider,
    migrations = listOf(DuplicateSecondaryUsernameMigration)
), NameReplacementStore {
    companion object {
        const val PREFERENCES_NAME = "platform-identity"

        val CREATION_STATE = stringPreferencesKey("creation_state")
        val CREATION_STATE_ERROR_MESSAGE = stringPreferencesKey("creation_state_error_message")
        val USERNAME = stringPreferencesKey("username")
        val USERNAME_SECONDARY = stringPreferencesKey("username_secondary")
        val IDENTITY_ID = stringPreferencesKey("identity_id")
        val RESTORING = booleanPreferencesKey("restoring")
        val ASSET_LOCK_TXID = stringPreferencesKey("asset_lock_txid")
        val USING_INVITE = booleanPreferencesKey("using_invite")
        val INVITE_LINK = stringPreferencesKey("invite_link")
        val PREORDER_SALT = stringPreferencesKey("preorder_salt")
        val PREORDER_SALT_SECONDARY = stringPreferencesKey("preorder_salt_secondary")
        val IDENTITY_REGISTRATION_STATUS = stringPreferencesKey("identity_registration_status")
        val USERNAME_REGISTRATION_STATUS = stringPreferencesKey("username_registration_status")
        val USERNAME_SECONDARY_REGISTRATION_STATUS = stringPreferencesKey("username_secondary_registration_status")

        val IDENTITY = stringPreferencesKey("identity")
        val PRIVACY_MODE = stringPreferencesKey("privacy_mode")
        val BALANCE = longPreferencesKey("identity_balance")

        val REQUESTED_USERNAME_LINK = stringPreferencesKey("requested_username_link")
        val CANCELED_REQUESTED_USERNAME_LINK = booleanPreferencesKey("cancelled_requested_username_link")
        val USERNAME_REQUESTED = stringPreferencesKey("username_requested")
        val VOTING_PERIOD_START = longPreferencesKey("voting_period_start")

        val REPLACEMENT_ID = stringPreferencesKey("replacement_id")
        val REPLACEMENT_IDENTITY_ID = stringPreferencesKey("replacement_identity_id")
        val REPLACEMENT_USERNAME = stringPreferencesKey("replacement_username")
        val REPLACEMENT_LINK = stringPreferencesKey("replacement_link")
        val REPLACEMENT_TOP_UP = longPreferencesKey("replacement_top_up")
        val REPLACEMENT_STATE = stringPreferencesKey("replacement_state")
        val REPLACEMENT_FAILURE = stringPreferencesKey("replacement_failure")
        val REPLACEMENT_FAILURE_MESSAGE = stringPreferencesKey("replacement_failure_message")
        val REPLACEMENT_TOP_UP_KEY = stringPreferencesKey("replacement_top_up_key")
        val REPLACEMENT_TOP_UP_TX = stringPreferencesKey("replacement_top_up_tx")
        val REPLACEMENT_REJECTED_STATE = stringPreferencesKey("replacement_rejected_state")
        val REPLACEMENT_REJECTED_ERROR = stringPreferencesKey("replacement_rejected_error")
        private val REPLACEMENT_KEYS = listOf(
            REPLACEMENT_ID, REPLACEMENT_IDENTITY_ID, REPLACEMENT_USERNAME, REPLACEMENT_LINK, REPLACEMENT_TOP_UP,
            REPLACEMENT_STATE, REPLACEMENT_FAILURE, REPLACEMENT_FAILURE_MESSAGE, REPLACEMENT_TOP_UP_KEY,
            REPLACEMENT_TOP_UP_TX, REPLACEMENT_REJECTED_STATE, REPLACEMENT_REJECTED_ERROR
        )
        private val log = LoggerFactory.getLogger(BlockchainIdentityConfig::class.java)
    }

    /** Unlike ordinary preference reads, absence here must never be inferred from an I/O fallback. */
    fun observeIdentityPresence(): Flow<Boolean> = context.dataStore.data.map { prefs ->
        IdentityCreationState.valueOf(prefs[CREATION_STATE] ?: "NONE") != IdentityCreationState.NONE ||
            prefs.asMap().keys.any { it != CREATION_STATE }
    }

    private val identityData: Flow<BlockchainIdentityData> = data
        .map { prefs ->
            BlockchainIdentityData(
                creationState = IdentityCreationState.valueOf(prefs[CREATION_STATE] ?: "NONE"),
                creationStateErrorMessage = prefs[CREATION_STATE_ERROR_MESSAGE],
                username = prefs[USERNAME],
                usernameSecondary = prefs[USERNAME_SECONDARY]?.takeIf { it != prefs[USERNAME] },
                userId = prefs[IDENTITY_ID],
                restoring = prefs[RESTORING] ?: false,
                creditFundingTxId = prefs[ASSET_LOCK_TXID]?.let { Sha256Hash.wrap(it) },
                identity = prefs[IDENTITY]?.let { platformService.dpp.identity.createFromBuffer(Converters.fromHex(it)) },
                usingInvite = prefs[USING_INVITE] ?: false,
                invite = prefs[INVITE_LINK]?.let { InvitationLinkData(Uri.parse(it), false) },
                preorderSalt = prefs[PREORDER_SALT]?.let { Converters.fromHex(it) },
                preorderSaltSecondary = prefs[PREORDER_SALT_SECONDARY]?.let { Converters.fromHex(it) },
                registrationStatus = prefs[IDENTITY_REGISTRATION_STATUS]?.let { IdentityStatus.valueOf(it) },
                usernameStatus = prefs[USERNAME_REGISTRATION_STATUS]?.let { UsernameStatus.valueOf(it) },
                usernameSecondaryStatus = prefs[USERNAME_SECONDARY_REGISTRATION_STATUS]?.let { UsernameStatus.valueOf(it) },
                privacyMode = prefs[PRIVACY_MODE]?.let { CoinJoinMode.valueOf(it) },
                creditBalance = prefs[BALANCE]?.let { Coin.valueOf(it) },
                verificationLink = prefs[REQUESTED_USERNAME_LINK],
                cancelledVerificationLink = prefs[CANCELED_REQUESTED_USERNAME_LINK],
                usernameRequested = prefs[USERNAME_REQUESTED]?.let { UsernameRequestStatus.valueOf(it) },
                votingPeriodStart = prefs[VOTING_PERIOD_START]
            )
        }

    private val identityBaseData: Flow<BlockchainIdentityBaseData> = data
        .map { prefs ->
            BlockchainIdentityBaseData(
                creationState = IdentityCreationState.valueOf(prefs[CREATION_STATE] ?: "NONE"),
                creationStateErrorMessage = prefs[CREATION_STATE_ERROR_MESSAGE],
                username = prefs[USERNAME],
                usernameSecondary = prefs[USERNAME_SECONDARY]?.takeIf { it != prefs[USERNAME] },
                userId = prefs[IDENTITY_ID],
                restoring = prefs[RESTORING] ?: false,
                creditFundingTxId = prefs[ASSET_LOCK_TXID]?.let { Sha256Hash.wrap(it) },
                usingInvite = prefs[USING_INVITE] ?: false,
                invite = prefs[INVITE_LINK]?.let { InvitationLinkData(it.toUri(), false) },
                verificationLink = prefs[REQUESTED_USERNAME_LINK],
                cancelledVerificationLink = prefs[CANCELED_REQUESTED_USERNAME_LINK],
                usernameRequested = prefs[USERNAME_REQUESTED]?.let { UsernameRequestStatus.valueOf(it) },
                votingPeriodStart = prefs[VOTING_PERIOD_START]
            )
        }

    suspend fun load() : BlockchainIdentityData? {
        val data = identityData.first()
        return if (data.creationState != IdentityCreationState.NONE) {
            data
        } else {
            null
        }
    }

    fun observe() : Flow<BlockchainIdentityData> {
        return identityData
    }

    suspend fun loadBase() : BlockchainIdentityBaseData {
        return identityBaseData.first()
    }

    fun observeBase() : Flow<BlockchainIdentityBaseData> {
        return identityBaseData
    }

    private suspend fun saveIdentityPrefs(blockchainIdentityData: BlockchainIdentityData) {
        updateCreationState(blockchainIdentityData.creationState, blockchainIdentityData.creationStateErrorMessage)
        context.dataStore.edit { prefs ->
            blockchainIdentityData.username?.let { prefs[USERNAME] = it }
            blockchainIdentityData.usernameSecondary?.let { prefs[USERNAME_SECONDARY] = it }
            blockchainIdentityData.userId?.let { prefs[IDENTITY_ID] = it }
            prefs[RESTORING] = blockchainIdentityData.restoring
            blockchainIdentityData.creditFundingTxId?.let { prefs[ASSET_LOCK_TXID] = it.toString() }
            blockchainIdentityData.identity?.let { prefs[IDENTITY] = it.toBuffer().toHex() }
            prefs[USING_INVITE] = blockchainIdentityData.usingInvite
            blockchainIdentityData.invite?.let { prefs[INVITE_LINK] = it.link.toString() }
            blockchainIdentityData.preorderSalt?.let { prefs[PREORDER_SALT] = it.toHex() }
            blockchainIdentityData.preorderSaltSecondary?.let { prefs[PREORDER_SALT_SECONDARY] = it.toHex() }
            blockchainIdentityData.registrationStatus?.let { prefs[IDENTITY_REGISTRATION_STATUS] = it.name }
            blockchainIdentityData.usernameStatus?.let { prefs[USERNAME_REGISTRATION_STATUS] = it.name }
            blockchainIdentityData.usernameSecondaryStatus?.let { prefs[USERNAME_SECONDARY_REGISTRATION_STATUS] = it.name }
            blockchainIdentityData.privacyMode?.let { prefs[PRIVACY_MODE] = it.name }
            blockchainIdentityData.creditBalance?.let { prefs[BALANCE] = it.value }
            blockchainIdentityData.usernameRequested?.let { prefs[USERNAME_REQUESTED] = it.name}
            blockchainIdentityData.verificationLink?.let { prefs[REQUESTED_USERNAME_LINK] = it }
            blockchainIdentityData.votingPeriodStart?.let { prefs[VOTING_PERIOD_START] = it }
            blockchainIdentityData.cancelledVerificationLink?.let { prefs[CANCELED_REQUESTED_USERNAME_LINK] = it }
        }
    }

    private suspend fun saveIdentityBasePrefs(blockchainIdentityBaseData: BlockchainIdentityBaseData) {
        updateCreationState(blockchainIdentityBaseData.creationState, blockchainIdentityBaseData.creationStateErrorMessage)
        context.dataStore.edit { prefs ->
            blockchainIdentityBaseData.username?.let { prefs[USERNAME] = it }
            blockchainIdentityBaseData.userId?.let { prefs[IDENTITY_ID] = it }
            prefs[RESTORING] = blockchainIdentityBaseData.restoring
            blockchainIdentityBaseData.creditFundingTxId?.let { prefs[ASSET_LOCK_TXID] = it.toString() }
            prefs[USING_INVITE] = blockchainIdentityBaseData.usingInvite
            blockchainIdentityBaseData.invite?.let { prefs[INVITE_LINK] = it.link.toString() }
            blockchainIdentityBaseData.usernameRequested?.let { prefs[USERNAME_REQUESTED] = it.name}
            blockchainIdentityBaseData.verificationLink?.let { prefs[REQUESTED_USERNAME_LINK] = it }
            blockchainIdentityBaseData.votingPeriodStart?.let { prefs[VOTING_PERIOD_START] = it }
            blockchainIdentityBaseData.cancelledVerificationLink?.let { prefs[CANCELED_REQUESTED_USERNAME_LINK] = it }
        }
    }

    suspend fun insert(blockchainIdentityData: BlockchainIdentityData) {
        saveIdentityPrefs(blockchainIdentityData)
    }

    suspend fun insert(blockchainIdentityBaseData: BlockchainIdentityBaseData) {
        saveIdentityBasePrefs(blockchainIdentityBaseData)
    }

    override suspend fun loadReplacement(): NameReplacementAttempt? = readReplacement(context.dataStore.data.first())

    override suspend fun approveReplacement(attempt: NameReplacementAttempt): Boolean = editReplacement { prefs ->
        val current = readReplacement(prefs)
        val inherited = current?.takeIf { it.state != NameReplacementState.NAME_SAVED }
        val rejectedError = inherited?.rejectedError ?: prefs[CREATION_STATE_ERROR_MESSAGE]
        if ((current != null && !current.canBeReplaced) || rejectedError == null ||
            !replacementApplies(prefs, attempt.identityId)
        ) {
            return@editReplacement false
        }
        writeReplacement(
            prefs,
            attempt.copy(
                state = NameReplacementState.APPROVED,
                failure = null,
                failureMessage = null,
                topUpKeyHash = null,
                topUpTx = null,
                rejectedState = inherited?.rejectedState ?: creationState(prefs),
                rejectedError = rejectedError
            )
        )
        prefs.remove(CREATION_STATE_ERROR_MESSAGE)
        true
    }

    override suspend fun markReplacementDispatching(id: String, topUpKeyHash: ByteArray) =
        transitionReplacement(id, NameReplacementState.APPROVED) { _, attempt ->
            attempt.copy(state = NameReplacementState.DISPATCHING, topUpKeyHash = topUpKeyHash)
        }

    override suspend fun markReplacementDispatched(id: String, topUpTx: ByteArray) =
        transitionReplacement(id, NameReplacementState.DISPATCHING) { _, attempt ->
            attempt.copy(state = NameReplacementState.DISPATCHED, topUpTx = topUpTx)
        }

    override suspend fun saveReplacementName(id: String, from: NameReplacementState) =
        transitionReplacement(id, from) { prefs, attempt ->
            if (!replacementApplies(prefs, attempt.identityId)) {
                clearReplacement(prefs)
                return@transitionReplacement null
            }
            prefs[USERNAME] = attempt.username
            listOf(
                USERNAME_SECONDARY, PREORDER_SALT, PREORDER_SALT_SECONDARY, USERNAME_REGISTRATION_STATUS,
                USERNAME_SECONDARY_REGISTRATION_STATUS, USERNAME_REQUESTED, VOTING_PERIOD_START,
                CANCELED_REQUESTED_USERNAME_LINK, CREATION_STATE_ERROR_MESSAGE
            ).forEach { prefs.remove(it) }
            if (attempt.verificationLink != null) {
                prefs[REQUESTED_USERNAME_LINK] = attempt.verificationLink
            } else {
                prefs.remove(REQUESTED_USERNAME_LINK)
            }
            prefs[CREATION_STATE] = IdentityCreationState.IDENTITY_REGISTERED.name
            attempt.copy(state = NameReplacementState.NAME_SAVED)
        }

    override suspend fun failReplacement(id: String, failure: NameReplacementFailure, message: String): Boolean =
        editReplacement { prefs ->
            val attempt = readReplacement(prefs)?.takeIf { it.id == id } ?: return@editReplacement false
            writeReplacement(prefs, attempt.copy(failure = failure, failureMessage = message))
            val restoresRejection = failure == NameReplacementFailure.FINAL &&
                attempt.state != NameReplacementState.NAME_SAVED
            when {
                restoresRejection && replacementApplies(prefs, attempt.identityId) -> {
                    attempt.rejectedState?.let { prefs[CREATION_STATE] = it.name }
                    prefs[CREATION_STATE_ERROR_MESSAGE] = attempt.rejectedError ?: message
                }
                !restoresRejection && ownsIdentity(prefs, attempt.identityId) ->
                    prefs[CREATION_STATE_ERROR_MESSAGE] = message
            }
            true
        }

    override suspend fun clearReplacementFailure(id: String): NameReplacementAttempt? = editReplacement { prefs ->
        val attempt = readReplacement(prefs)
            ?.takeIf { it.id == id && it.failure == NameReplacementFailure.RETRYABLE }
            ?: return@editReplacement null
        if (ownsIdentity(prefs, attempt.identityId)) {
            prefs.remove(CREATION_STATE_ERROR_MESSAGE)
        }
        attempt.copy(failure = null, failureMessage = null).also { writeReplacement(prefs, it) }
    }

    override suspend fun completeReplacement(id: String) {
        context.dataStore.edit { prefs ->
            if (prefs[REPLACEMENT_ID] == id) {
                clearReplacement(prefs)
            }
        }
    }

    private suspend fun transitionReplacement(
        id: String,
        from: NameReplacementState,
        transform: (MutablePreferences, NameReplacementAttempt) -> NameReplacementAttempt?
    ): NameReplacementAttempt? = editReplacement { prefs ->
        val attempt = readReplacement(prefs)
            ?.takeIf { it.id == id && it.state == from && it.failure == null }
            ?: return@editReplacement null
        transform(prefs, attempt)?.also { writeReplacement(prefs, it) }
    }

    private suspend fun <T> editReplacement(block: (MutablePreferences) -> T): T {
        var result: T? = null
        context.dataStore.edit { prefs -> result = block(prefs) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun creationState(prefs: Preferences) =
        IdentityCreationState.valueOf(prefs[CREATION_STATE] ?: IdentityCreationState.NONE.name)

    private fun ownsIdentity(prefs: Preferences, identityId: String) =
        prefs[IDENTITY_ID] == identityId && prefs[USING_INVITE] == true

    private fun replacementApplies(prefs: Preferences, identityId: String) =
        ownsIdentity(prefs, identityId) &&
            prefs[USERNAME_SECONDARY]?.takeIf { it != prefs[USERNAME] } == null &&
            creationState(prefs) in IdentityCreationState.IDENTITY_REGISTERED..IdentityCreationState.USERNAME_REGISTERING

    private fun clearReplacement(prefs: MutablePreferences) = REPLACEMENT_KEYS.forEach { prefs.remove(it) }

    private fun readReplacement(prefs: Preferences): NameReplacementAttempt? {
        val id = prefs[REPLACEMENT_ID] ?: return null
        return NameReplacementAttempt(
            id = id,
            identityId = prefs[REPLACEMENT_IDENTITY_ID] ?: return null,
            username = prefs[REPLACEMENT_USERNAME] ?: return null,
            verificationLink = prefs[REPLACEMENT_LINK],
            approvedTopUp = Coin.valueOf(prefs[REPLACEMENT_TOP_UP] ?: return null),
            state = NameReplacementState.valueOf(prefs[REPLACEMENT_STATE] ?: return null),
            failure = prefs[REPLACEMENT_FAILURE]?.let { NameReplacementFailure.valueOf(it) },
            failureMessage = prefs[REPLACEMENT_FAILURE_MESSAGE],
            topUpKeyHash = prefs[REPLACEMENT_TOP_UP_KEY]?.let { Converters.fromHex(it) },
            topUpTx = prefs[REPLACEMENT_TOP_UP_TX]?.let { Converters.fromHex(it) },
            rejectedState = prefs[REPLACEMENT_REJECTED_STATE]?.let { IdentityCreationState.valueOf(it) },
            rejectedError = prefs[REPLACEMENT_REJECTED_ERROR]
        )
    }

    private fun writeReplacement(prefs: MutablePreferences, attempt: NameReplacementAttempt) {
        clearReplacement(prefs)
        prefs[REPLACEMENT_ID] = attempt.id
        prefs[REPLACEMENT_IDENTITY_ID] = attempt.identityId
        prefs[REPLACEMENT_USERNAME] = attempt.username
        attempt.verificationLink?.let { prefs[REPLACEMENT_LINK] = it }
        prefs[REPLACEMENT_TOP_UP] = attempt.approvedTopUp.value
        prefs[REPLACEMENT_STATE] = attempt.state.name
        attempt.failure?.let { prefs[REPLACEMENT_FAILURE] = it.name }
        attempt.failureMessage?.let { prefs[REPLACEMENT_FAILURE_MESSAGE] = it }
        attempt.topUpKeyHash?.let { prefs[REPLACEMENT_TOP_UP_KEY] = it.toHex() }
        attempt.topUpTx?.let { prefs[REPLACEMENT_TOP_UP_TX] = it.toHex() }
        attempt.rejectedState?.let { prefs[REPLACEMENT_REJECTED_STATE] = it.name }
        attempt.rejectedError?.let { prefs[REPLACEMENT_REJECTED_ERROR] = it }
    }

    suspend fun updateCreationState(state: IdentityCreationState, creationStateErrorMessage: String?) {
        context.dataStore.edit { prefs ->
            prefs[CREATION_STATE] = state.name
            if (creationStateErrorMessage == null) {
                prefs.remove(CREATION_STATE_ERROR_MESSAGE)
            } else {
                prefs[CREATION_STATE_ERROR_MESSAGE] = creationStateErrorMessage
            }
        }
    }

    suspend fun clear() {
        log.info("clearing data")
        clearAll()
    }
}

/** Earlier versions saved the primary username as the secondary one whenever they reloaded the identity. */
internal object DuplicateSecondaryUsernameMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val secondary = currentData[BlockchainIdentityConfig.USERNAME_SECONDARY]
        return secondary != null && secondary == currentData[BlockchainIdentityConfig.USERNAME]
    }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            remove(BlockchainIdentityConfig.USERNAME_SECONDARY)
            remove(BlockchainIdentityConfig.USERNAME_SECONDARY_REGISTRATION_STATUS)
            remove(BlockchainIdentityConfig.PREORDER_SALT_SECONDARY)
        }.toPreferences()

    override suspend fun cleanUp() {}
}
