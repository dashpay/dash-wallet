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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import javax.inject.Inject
import javax.inject.Singleton

data class BlockchainIdentityData(
    var creationState: CreationState = CreationState.NONE,
    var creationStateErrorMessage: String?,
    var username: String?,
    var userId: String?,
    var restoring: Boolean,
    var identity: Identity? = null,
    var creditFundingTxId: Sha256Hash? = null,
    var usingInvite: Boolean = false,
    var invite: InvitationLinkData? = null,
    var preorderSalt: ByteArray? = null,
    var registrationStatus: IdentityStatus? = null,
    var usernameStatus: UsernameStatus? = null,
    var privacyMode: CoinJoinMode? = null,
    var creditBalance: Coin? = null,
    var activeKeyCount: Int? = null,
    var totalKeyCount: Int? = null,
    var keysCreated: Long? = null,
    var currentMainKeyIndex: Int? = null,
    var currentMainKeyType: KeyType? = null,
    var verificationLink: String? = null,
    val cancelledVerificationLink: Boolean? = null,
    var usernameRequested: UsernameRequestStatus? = null,
    var votingPeriodStart: Long? = null
    ) {

    var id = 1
        set(@Suppress("UNUSED_PARAMETER") value) {
            field = 1
        }

    private var creditFundingTransactionCache: AssetLockTransaction? = null

    fun findAssetLockTransaction(wallet: Wallet?): AssetLockTransaction? {
        if (creditFundingTxId == null) {
            val authExtension =
                wallet!!.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
            val list = authExtension.assetLockTransactions
            list.sortBy { it.updateTime }
            return if (list.isEmpty()) null else list.first()
        }
        if (wallet != null) {
            creditFundingTransactionCache = wallet.getTransaction(creditFundingTxId)?.run {
                val authExtension = wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension
                authExtension.getAssetLockTransaction(this)
            }
        }
        return creditFundingTransactionCache
    }

    fun getIdentity(wallet: Wallet?): String? = findAssetLockTransaction(wallet)?.let { it.identityId.toStringBase58() }

    fun getErrorMetadata() = creationStateErrorMessage?.let {
            val metadataIndex = it.indexOf("Metadata(")
            it.substring(metadataIndex)
    }

    enum class CreationState {
        NONE,   // this should always be the first value
        UPGRADING_WALLET,
        CREDIT_FUNDING_TX_CREATING,
        CREDIT_FUNDING_TX_SENDING,
        CREDIT_FUNDING_TX_SENT,
        CREDIT_FUNDING_TX_CONFIRMED,
        IDENTITY_REGISTERING,
        IDENTITY_REGISTERED,
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

    fun finishRestoration() {
        this.restoring = false
        this.creationStateErrorMessage = null
    }
}

@Singleton
open class BlockchainIdentityConfig @Inject constructor(
    private val context: Context,
    walletDataProvider: WalletDataProvider,
    val platformService: PlatformService
) : BaseConfig(
    context,
    PREFERENCES_NAME,
    walletDataProvider,
    migrations = listOf()
) {
    companion object {
        const val PREFERENCES_NAME = "platform-identity"

        val CREATION_STATE = stringPreferencesKey("creation_state")
        val CREATION_STATE_ERROR_MESSAGE = stringPreferencesKey("creation_state_error_message")
        val USERNAME = stringPreferencesKey("username")
        val IDENTITY_ID = stringPreferencesKey("identity_id")
        val RESTORING = booleanPreferencesKey("restoring")
        val ASSET_LOCK_TXID = stringPreferencesKey("asset_lock_txid")
        val USING_INVITE = booleanPreferencesKey("using_invite")
        val INVITE_LINK = stringPreferencesKey("invite_link")
        val PREORDER_SALT = stringPreferencesKey("preorder_salt")
        val IDENTITY_REGISTRATION_STATUS = stringPreferencesKey("identity_registration_status")
        val USERNAME_REGISTRATION_STATUS = stringPreferencesKey("username_registration_status")
        val IDENTITY = stringPreferencesKey("identity")
        val PRIVACY_MODE = stringPreferencesKey("privacy_mode")
        val BALANCE = longPreferencesKey("identity_balance")

        // val REQUESTED_USERNAME = stringPreferencesKey("requested_username")
        val REQUESTED_USERNAME_LINK = stringPreferencesKey("requested_username_link")
        val CANCELED_REQUESTED_USERNAME_LINK = booleanPreferencesKey("cancelled_requested_username_link")
        val USERNAME_REQUESTED = stringPreferencesKey("username_requested")
        val VOTING_PERIOD_START = longPreferencesKey("voting_period_start")

    }

    private val identityData: Flow<BlockchainIdentityData> = data
        .map { prefs ->
            BlockchainIdentityData(
                creationState = BlockchainIdentityData.CreationState.valueOf(prefs[CREATION_STATE] ?: "NONE"),
                creationStateErrorMessage = prefs[CREATION_STATE_ERROR_MESSAGE],
                username = prefs[USERNAME],
                userId = prefs[IDENTITY_ID],
                restoring = prefs[RESTORING] ?: false,
                creditFundingTxId = prefs[ASSET_LOCK_TXID]?.let { Sha256Hash.wrap(it) },
                identity = prefs[IDENTITY]?.let { platformService.dpp.identity.createFromBuffer(Converters.fromHex(it)) },
                usingInvite = prefs[USING_INVITE] ?: false,
                invite = prefs[INVITE_LINK]?.let { InvitationLinkData(Uri.parse(it), false) },
                preorderSalt = prefs[PREORDER_SALT]?.let { Converters.fromHex(it) },
                registrationStatus = prefs[IDENTITY_REGISTRATION_STATUS]?.let { IdentityStatus.valueOf(it) },
                usernameStatus = prefs[USERNAME_REGISTRATION_STATUS]?.let { UsernameStatus.valueOf(it) },
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
                1,
                creationState = BlockchainIdentityData.CreationState.valueOf(prefs[CREATION_STATE] ?: "NONE"),
                creationStateErrorMessage = prefs[CREATION_STATE_ERROR_MESSAGE],
                username = prefs[USERNAME],
                userId = prefs[IDENTITY_ID],
                restoring = prefs[RESTORING] ?: false,
                creditFundingTxId = prefs[ASSET_LOCK_TXID]?.let { Sha256Hash.wrap(it) },
                usingInvite = prefs[USING_INVITE] ?: false,
                invite = prefs[INVITE_LINK]?.let { InvitationLinkData(Uri.parse(it), false) },
                verificationLink = prefs[REQUESTED_USERNAME_LINK],
                cancelledVerificationLink = prefs[CANCELED_REQUESTED_USERNAME_LINK],
                usernameRequested = prefs[USERNAME_REQUESTED]?.let { UsernameRequestStatus.valueOf(it) },
                votingPeriodStart = prefs[VOTING_PERIOD_START]
            )
        }

    suspend fun load() : BlockchainIdentityData? {
        val data = identityData.first()
        return if (data.creationState != BlockchainIdentityData.CreationState.NONE) {
            data
        } else {
            null
        }
    }

    suspend fun loadBase() : BlockchainIdentityBaseData {
        return identityBaseData.first()
    }

    fun observeBase() : Flow<BlockchainIdentityBaseData> {
        return identityBaseData
    }

    private suspend fun saveIdentityPrefs(blockchainIdentityData: BlockchainIdentityData) {
        updateCreationState(1, blockchainIdentityData.creationState, blockchainIdentityData.creationStateErrorMessage)
        context.dataStore.edit { prefs ->
            blockchainIdentityData.username?.let { prefs[USERNAME] = it }
            blockchainIdentityData.userId?.let { prefs[IDENTITY_ID] = it }
            prefs[RESTORING] = blockchainIdentityData.restoring
            blockchainIdentityData.creditFundingTxId?.let { prefs[ASSET_LOCK_TXID] = it.toString() }
            blockchainIdentityData.identity?.let { prefs[IDENTITY] = it.toBuffer().toHex() }
            prefs[USING_INVITE] = blockchainIdentityData.usingInvite
            blockchainIdentityData.invite?.let { prefs[INVITE_LINK] = it.link.toString() }
            blockchainIdentityData.registrationStatus?.let { prefs[IDENTITY_REGISTRATION_STATUS] = it.name }
            blockchainIdentityData.usernameStatus?.let { prefs[USERNAME_REGISTRATION_STATUS] = it.name }
            blockchainIdentityData.privacyMode?.let { prefs[PRIVACY_MODE] = it.name }
            blockchainIdentityData.creditBalance?.let { prefs[BALANCE] = it.value }
            blockchainIdentityData.usernameRequested?.let { prefs[USERNAME_REQUESTED] = it.name}
            blockchainIdentityData.verificationLink?.let { prefs[REQUESTED_USERNAME_LINK] = it }
            blockchainIdentityData.votingPeriodStart?.let { prefs[VOTING_PERIOD_START] = it }
            blockchainIdentityData.cancelledVerificationLink?.let { prefs[CANCELED_REQUESTED_USERNAME_LINK] = it }
        }
    }

    private suspend fun saveIdentityBasePrefs(blockchainIdentityBaseData: BlockchainIdentityBaseData) {
        updateCreationState(1, blockchainIdentityBaseData.creationState, blockchainIdentityBaseData.creationStateErrorMessage)
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

    suspend fun updateCreationState(id: Int, state: BlockchainIdentityData.CreationState, creationStateErrorMessage: String?) {
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
        clearAll()
    }
}