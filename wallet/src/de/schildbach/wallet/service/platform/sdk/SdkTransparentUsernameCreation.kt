/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.service.platform.sdk

import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.work.RestoreIdentityOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashfoundation.dashsdk.identity.IdentityPubkey
import org.dashfoundation.dashsdk.identity.RegistrationKeys
import org.dashfoundation.dashsdk.identity.RegistrationKeySet
import org.dashfoundation.dashsdk.wallet.TrackedAssetLock
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Shared registration-row plumbing ──────────────────────────────────

/**
 * Stamp the canonical DPP roles onto a DERIVED preview key set, producing
 * the rich [IdentityPubkey] registration rows every SDK funding/claim FFI
 * takes. Delegates to the SDK's own [RegistrationKeys.buildRegistrationRows]
 * — the single source of truth for the role table (keyId 0 AUTH/MASTER, 1
 * AUTH/CRITICAL, 2 AUTH/HIGH, 3 TRANSFER/CRITICAL, and — when the DashPay
 * pair is present — 4 ENCRYPTION, 5 DECRYPTION bound to the DashPay
 * `contactRequest` document type) — so every wallet path stays byte-for-byte
 * identical to the SDK's own paths.
 *
 * The DashPay choice is DERIVED from the size of the set the caller actually
 * derived ([RegistrationKeys.keyCount]) rather than passed in a second time:
 * [RegistrationKeys.buildRegistrationRows] validates the count EXACTLY, so a
 * flag that disagreed with the preview count would throw. Deriving it here
 * makes that drift structurally impossible — the ONE decision each call path
 * makes is how many keys to preview.
 *
 * Shared by [DashSdkTransparentUsernameSource], [DashSdkL1InviteSource] and
 * [DashSdkShieldedUsernameSource]; no private material is touched.
 */
internal fun registrationRowsFor(keys: List<IdentityKeyPreview>): List<IdentityPubkey> =
    RegistrationKeys.buildRegistrationRows(
        publicKeys = keys.map { it.publicKey },
        includeDashPayKeys = keys.size == RegistrationKeys.keyCount(includeDashPayKeys = true)
    )

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over the Kotlin SDK's TRANSPARENT (non-shielded) identity-funding
 * surface ([org.dashfoundation.dashsdk.wallet.PlatformWalletManager]'s
 * identity-registration slice), so the resume-gate / no-double-broadcast
 * orchestration in [SdkTransparentUsernameCreation] is host-JVM
 * unit-testable — the real calls need `libdash_sdk`.
 *
 * The key-row plumbing mirrors [ShieldedUsernameSource]
 * (previewRegistrationKeySet → storeIdentityPrivateKey → registerDpnsName):
 * each registration row's private scalar is persisted into WalletStorage
 * (storeIdentityPrivateKey) before the funding call because the FFI signer
 * resolves identity signing keys by LOOKUP, not derivation. The difference is
 * the FUNDING call:
 * instead of spending the shielded pool
 * ([ShieldedUsernameSource.createIdentityFromPool]) it builds an L1 asset
 * lock from the wallet's transparent Core UTXOs, held by the SDK post-cutover.
 */
interface TransparentUsernameSource {
    /** Same contract as [SdkDashPayWriteSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * Number of identities the bound SDK wallet already manages — the next
     * free identity index on the DIP-9 identity chain (0 for the
     * fresh-wallet create path this flow serves).
     */
    suspend fun managedIdentityCount(walletIdHex: String): Int

    /**
     * Derive the full canonical registration key SET (keyId 0 MASTER/AUTH,
     * 1 CRITICAL/AUTH, 2 HIGH/AUTH, 3 TRANSFER/CRITICAL — plus, when
     * [includeDashPayKeys], 4 ENCRYPTION and 5 DECRYPTION bound to DashPay's
     * `contactRequest` document type) for [identityIndex]. Pure compute — no
     * Platform RPCs, nothing persisted.
     *
     * [includeDashPayKeys] is the ONE per-call-path decision (see
     * [registrationRowsFor]): true for a FRESH-FUNDING registration, which
     * commits its key set for the first time and must carry the DashPay pair
     * so the identity can send contact requests without falling back to
     * dashj; false for an ASSET-LOCK RESUME, which must never grow past the
     * key set the killed attempt already committed to.
     */
    suspend fun previewRegistrationKeySet(
        walletIdHex: String,
        identityIndex: Int,
        includeDashPayKeys: Boolean
    ): List<IdentityKeyPreview>

    /**
     * Persist the private [privateKey] scalar of ONE registration key
     * (whose compressed public half is [pubkeyHex]) into the SDK's
     * Keystore-backed `WalletStorage`, owned by [walletIdHex]. This is the
     * precondition the FFI identity signer requires before
     * [registerWithWalletFunding] / [resumeRegistrationFromAssetLock]: the
     * signer resolves each registration key's private half by LOOKUP
     * (`retrievePrivateKey(pubkeyHex)`) — identity keys are never derived by
     * the signer — so an unstored key throws `SigningKeyUnavailable`. A
     * public-key encrypt: never auth-gated, safe unprompted. Throws on
     * failure. See [DashSdkService.storeIdentityPrivateKey].
     */
    suspend fun storeIdentityPrivateKey(walletIdHex: String, pubkeyHex: String, privateKey: ByteArray)

    /**
     * RESUME-GATE query: the UNRESOLVED IdentityRegistration asset lock the
     * SDK durably tracks for [identityIndex] (a killed prior attempt that
     * already built — and possibly broadcast — an asset lock), or null when
     * none is outstanding. Backed by
     * [org.dashfoundation.dashsdk.wallet.PlatformWalletManager.trackedIdentityRecoveryAssetLocks],
     * which is the SDK's RECOVERY query — it returns only locks whose
     * identity registration has not completed, so any returned lock matching
     * [TrackedAssetLock.FundingType.IDENTITY_REGISTRATION] at
     * [TrackedAssetLock.getRegistrationIndex] == [identityIndex] must be
     * RESUMED, never rebuilt (rebuilding selects fresh UTXOs = DOUBLE-PAY).
     */
    suspend fun unresolvedRegistrationAssetLock(walletIdHex: String, identityIndex: Int): TrackedAssetLock?

    /**
     * RELIABLE post-success txid capture: the DISPLAY-order txid hex of the
     * IDENTITY_REGISTRATION asset lock the SDK persisted for [identityIndex],
     * or null when none is found. Reads the SDK's `asset_locks` Room table
     * (which RETAINS consumed rows) rather than the ELIGIBLE-ONLY native
     * recovery surface ([unresolvedRegistrationAssetLock]) that DROPS a lock
     * the fresh register just consumed — the source of the "Internal"
     * mislabel. The table's `outPointHex` is already `"<display-txid>:<vout>"`,
     * so the returned value needs no wire→display conversion.
     */
    suspend fun registrationAssetLockTxidDisplayHex(walletIdHex: String, identityIndex: Int): String?

    /**
     * PLAIN build: create + register a new identity at [identityIndex]
     * funded by a FRESH asset lock built from the wallet's transparent Core
     * UTXOs ([amountDuffs] Core duffs from BIP44 [accountIndex]). Never
     * consults existing tracked locks — the caller MUST run the resume gate
     * first. Returns the new 32-byte identity id.
     *
     * FRESH FUNDING, so [keys] is the SIX-key set (previewed with
     * `includeDashPayKeys = true`): this transition commits the identity's
     * key set for the first time, and the DashPay ENCRYPTION/DECRYPTION pair
     * can only ever be committed here.
     */
    suspend fun registerWithWalletFunding(
        walletIdHex: String,
        amountDuffs: Long,
        accountIndex: Int,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>
    ): ByteArray

    /**
     * RESUME build: create + register the identity at [identityIndex]
     * reusing the SAME [lock] a prior attempt already built (no fresh UTXO
     * selection — the only re-entry that cannot double-pay). Returns the new
     * 32-byte identity id.
     *
     * RESUME, so [keys] is the BASE FOUR-key set (previewed with
     * `includeDashPayKeys = false`): a resume re-drives the registration the
     * killed attempt already committed to and must never grow past that key
     * set (`RegistrationKeys`' KDoc rule). An identity resumed this way has
     * no DashPay ENCRYPTION key, so its contact requests keep taking the
     * dashj fallback — the pre-existing behaviour, not a new regression.
     */
    suspend fun resumeRegistrationFromAssetLock(
        walletIdHex: String,
        lock: TrackedAssetLock,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>
    ): ByteArray

    /**
     * Register [label] as a DPNS name for [identityId]. Contested labels
     * need no special client-side handling (dpp derives the prefunded voting
     * balance from the identity's credit balance). Returns the full domain
     * name (e.g. `"alice.dash"`).
     */
    suspend fun registerDpnsName(walletIdHex: String, identityId: ByteArray, label: String): String
}

/** Production [TransparentUsernameSource]: boots the SDK on demand. */
internal class DashSdkTransparentUsernameSource(
    private val service: DashSdkService
) : TransparentUsernameSource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    private suspend fun wallet(walletIdHex: String): org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet =
        checkNotNull(manager().wallets.value[walletIdHex]) { "SDK wallet not loaded" }

    private fun walletId(walletIdHex: String): ByteArray =
        checkNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }

    override suspend fun boundWalletIdOrNull(): String? =
        manager().wallets.value.keys.singleOrNull()

    override suspend fun managedIdentityCount(walletIdHex: String): Int =
        wallet(walletIdHex).inMemoryIdentityIds().size

    override suspend fun previewRegistrationKeySet(
        walletIdHex: String,
        identityIndex: Int,
        includeDashPayKeys: Boolean
    ): List<IdentityKeyPreview> {
        val manager = manager()
        return manager.identityRegistration.previewRegistrationKeySet(
            walletHandle = wallet(walletIdHex).handle,
            mnemonicResolverHandle = manager.mnemonicResolverHandle,
            identityIndex = identityIndex,
            // The SDK default (-1) derives only the base four; ask for the
            // exact count the rows will be built for.
            count = RegistrationKeys.keyCount(includeDashPayKeys)
        )
    }

    override suspend fun storeIdentityPrivateKey(
        walletIdHex: String,
        pubkeyHex: String,
        privateKey: ByteArray
    ) {
        service.storeIdentityPrivateKey(pubkeyHex, privateKey, walletId(walletIdHex))
    }

    override suspend fun unresolvedRegistrationAssetLock(
        walletIdHex: String,
        identityIndex: Int
    ): TrackedAssetLock? =
        manager().trackedIdentityRecoveryAssetLocks(walletId(walletIdHex))
            .firstOrNull {
                it.fundingType == TrackedAssetLock.FundingType.IDENTITY_REGISTRATION &&
                    it.registrationIndex == identityIndex
            }

    override suspend fun registrationAssetLockTxidDisplayHex(
        walletIdHex: String,
        identityIndex: Int
    ): String? =
        service.databaseOrNull()?.assetLockDao()
            ?.observeByWalletAndIdentityIndex(walletId(walletIdHex), identityIndex)
            ?.first()
            ?.firstOrNull {
                it.fundingTypeRaw == TrackedAssetLock.FundingType.IDENTITY_REGISTRATION.raw
            }
            ?.outPointHex?.substringBefore(':')

    override suspend fun registerWithWalletFunding(
        walletIdHex: String,
        amountDuffs: Long,
        accountIndex: Int,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>
    ): ByteArray {
        val manager = manager()
        return manager.identityRegistration.registerWithWalletFunding(
            wallet(walletIdHex).handle,
            amountDuffs,
            accountIndex,
            identityIndex,
            registrationRowsFor(keys),
            manager.signerHandle,
            manager.mnemonicResolverHandle
        )
    }

    override suspend fun resumeRegistrationFromAssetLock(
        walletIdHex: String,
        lock: TrackedAssetLock,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>
    ): ByteArray {
        val manager = manager()
        return manager.identityRegistration.resumeWithExistingAssetLock(
            wallet(walletIdHex).handle,
            lock,
            identityIndex,
            RegistrationKeySet(identityIndex, registrationRowsFor(keys)),
            manager.signerHandle,
            manager.mnemonicResolverHandle
        )
    }

    override suspend fun registerDpnsName(
        walletIdHex: String,
        identityId: ByteArray,
        label: String
    ): String {
        val manager = manager()
        return manager.identityRegistration.registerDpnsName(
            walletHandle = wallet(walletIdHex).handle,
            identityId = identityId,
            label = label,
            signerHandle = manager.signerHandle
        )
    }
}

// ── Service ───────────────────────────────────────────────────────────

/**
 * TRANSPARENT-funded username creation — the post-cutover replacement for
 * the dashj identity-funding path in [de.schildbach.wallet.ui.dashpay
 * .CreateIdentityService]. Once the cutover is committed the dashj L1
 * engine is HELD (0 UTXOs), so building the identity's asset lock with
 * dashj fails `InsufficientMoneyException` — the funds live in the SDK. This
 * routes REGISTRATION funding through the SDK's
 * `registerWithWalletFunding` (or, on re-entry, `resumeWithExistingAssetLock`)
 * so the identity is funded from the transparent UTXOs the SDK now holds,
 * then registers the DPNS name and hands the on-chain identity to the same
 * [RestoreIdentityOperation] restore machinery the shielded path uses.
 *
 * Structural sibling of [SdkShieldedUsernameCreation] — same three-valued
 * [SdkWriteResult] contract, same app-scope single-flight [submit] state
 * machine, same legacy handoff. Differences:
 * - the funding source is transparent Core UTXOs, not the Orchard pool, so
 *   there is no denomination mapping / shielded-balance preflight / Type-20
 *   fallback address;
 * - the gate is the COMMITTED CUTOVER ([cutoverCommitted]) rather than the
 *   shielded soak flag — pre-cutover this path is never routed to and every
 *   entry point fails closed to [SdkWriteResult.NotBroadcast];
 * - a MANDATORY RESUME GATE ([TransparentUsernameSource.unresolvedRegistrationAssetLock])
 *   runs before every plain funding call. The SDK commits the asset lock at
 *   status Built to SQLite SYNCHRONOUSLY before broadcasting, so a killed +
 *   retried creation reliably finds the same tracked lock and RESUMES it
 *   (re-consuming the SAME UTXOs) instead of building a second (different
 *   UTXO selection = DOUBLE-PAY). The plain FFI never auto-resumes.
 *
 * Funds safety: there is NO dashj↔SDK fallback on a committed cutover. A
 * funding failure that is provably pre-broadcast is [SdkWriteResult.NotBroadcast]
 * (retry-safe — the resume gate re-consumes any lock a retry finds); anything
 * else is [SdkWriteResult.Ambiguous] — STICKY, never auto-rebuilt (only the
 * resume gate may re-enter after a process restart).
 */
@Singleton
class SdkTransparentUsernameCreation internal constructor(
    private val source: TransparentUsernameSource,
    /**
     * True once the cutover is COMMITTED (persisted CUT_OVER / SETTLED — the
     * dashj engine is held). This path is only ever ROUTED to post-cutover;
     * the gate is a belt-and-suspenders fail-closed so a stray pre-cutover
     * call submits nothing.
     */
    private val cutoverCommitted: suspend () -> Boolean,
    /**
     * Identity-registration funding amount in Core DUFFS for the given
     * contested-ness (prod: `DASH_PAY_FEE_CONTESTED` = 0.25 DASH for
     * contested labels, `DASH_PAY_FEE` = 0.03 DASH otherwise) — the same fee
     * the dashj path funds the asset lock with
     * ([de.schildbach.wallet.service.platform.TopUpRepository.createAssetLockTransaction]).
     */
    private val feeDuffs: (contested: Boolean) -> Long,
    /**
     * Hands the freshly created on-chain identity to the legacy state
     * machine (prod: enqueue [RestoreIdentityOperation]) — best-effort,
     * never affects the returned result.
     */
    private val handOffToLegacy: (identityIdBase58: String) -> Unit,
    /**
     * Persist the identity funding asset-lock txid (DISPLAY-order hex, i.e.
     * `Sha256Hash.toString()` form) so the app-side records can label this L1
     * asset lock as an "Upgrade" instead of the SDK's neutral "Internal"
     * classification. Best-effort — no-op default keeps the host-JVM tests
     * (which have no DataStore) inert.
     */
    private val persistFundingTxid: suspend (displayHex: String) -> Unit = {},
    /**
     * Seed the asset-lock kind ([AssetLockKind.UPGRADE]) in-memory the instant
     * the funding txid is known, so the FIRST time the engine feed classifies
     * this L1 asset lock it already reads as "Upgrade" instead of momentarily
     * showing "Internal" until the [persistFundingTxid] DataStore write lands
     * (the visible-flip fix). Best-effort — no-op default keeps the host-JVM
     * tests inert.
     */
    private val seedAssetLockKind: (displayHex: String, kind: AssetLockKind) -> Unit = { _, _ -> },
    /**
     * Drive the persisted identity CREATION STATE (and error message) — the
     * same [de.schildbach.wallet.database.entity.BlockchainIdentityConfig]
     * seam the classic [CreateIdentityService] path and the restore worker
     * use — so the home tile reflects the DPNS registration step and, on
     * failure, its retryable error. No-op default keeps the host-JVM tests
     * (which have no DataStore) inert.
     */
    private val driveCreationState: suspend (state: IdentityCreationState, errorMessage: String?) -> Unit =
        { _, _ -> },
    /**
     * Persist the identity id + requested label(s) + the `restoring` flag so
     * the tile has context during (and after) the DPNS step. On a name
     * FAILURE this is called with `restoring = true` so a tile retry routes
     * to [RestoreIdentityOperation] (the worker re-drives ONLY the DPNS step
     * for the EXISTING identity — never re-funds), NOT the dashj retry.
     * No-op default keeps the host-JVM tests inert.
     */
    private val persistNameContext: suspend (
        identityIdBase58: String,
        username: String,
        secondaryUsername: String?,
        restoring: Boolean
    ) -> Unit = { _, _, _, _ -> },
    /** Scope for [submit]; null (tests' default path) makes submit inert. */
    private val executorScope: CoroutineScope? = null
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        sdkL1SendService: SdkL1SendService,
        walletApplication: WalletApplication,
        blockchainIdentityConfig: BlockchainIdentityConfig,
        assetLockKindResolver: AssetLockKindResolver,
        applicationScope: CoroutineScope
    ) : this(
        source = DashSdkTransparentUsernameSource(sdkService),
        cutoverCommitted = { sdkL1SendService.cutoverCommitted() },
        // Lazy: Constants untouched at construction (inert-until-called).
        feeDuffs = { contested ->
            if (contested) Constants.DASH_PAY_FEE_CONTESTED.value else Constants.DASH_PAY_FEE.value
        },
        handOffToLegacy = { identityId ->
            RestoreIdentityOperation(walletApplication).create(identityId, fromCreation = true).enqueue()
        },
        persistFundingTxid = { displayHex ->
            blockchainIdentityConfig.set(BlockchainIdentityConfig.ASSET_LOCK_TXID, displayHex)
        },
        seedAssetLockKind = { displayHex, kind ->
            assetLockKindResolver.seed(displayHex, kind)
        },
        driveCreationState = { state, errorMessage ->
            blockchainIdentityConfig.updateCreationState(state, errorMessage)
        },
        persistNameContext = { identityIdBase58, username, secondaryUsername, restoring ->
            blockchainIdentityConfig.set(BlockchainIdentityConfig.IDENTITY_ID, identityIdBase58)
            blockchainIdentityConfig.set(BlockchainIdentityConfig.USERNAME, username)
            secondaryUsername?.let {
                blockchainIdentityConfig.set(BlockchainIdentityConfig.USERNAME_SECONDARY, it)
            }
            blockchainIdentityConfig.set(BlockchainIdentityConfig.RESTORING, restoring)
        },
        executorScope = applicationScope
    )

    /** Test seam: the funding call blocks on network I/O — keep it off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _submitState =
        MutableStateFlow<ShieldedUsernameSubmitState>(ShieldedUsernameSubmitState.Idle)

    /** The in-flight/terminal state of the one allowed creation operation. */
    val submitState: StateFlow<ShieldedUsernameSubmitState> = _submitState.asStateFlow()

    /**
     * Whether the cutover is committed — the routing signal
     * [de.schildbach.wallet.ui.username.request.RequestUserNameViewModel.submit]
     * uses to pick this path over the dashj [CreateIdentityService] path.
     * Contained: a read failure reads as NOT committed (dashj rules).
     */
    suspend fun isCutoverCommitted(): Boolean = try {
        cutoverCommitted()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        log.warn("failed to read the cutover state; treating as not committed", e)
        false
    }

    /**
     * Start the creation on the application scope. Returns false — and
     * submits NOTHING — unless the current state is Idle or the
     * provably-pre-broadcast NotSent (retry-safe). The Idle→Proving
     * transition is atomic under [this].
     */
    fun submit(username: String, secondaryUsername: String? = null): Boolean {
        val scope = executorScope
        if (scope == null) {
            log.warn("transparent username creation submit refused: no executor scope")
            return false
        }
        synchronized(this) {
            val state = _submitState.value
            if (state != ShieldedUsernameSubmitState.Idle &&
                state !is ShieldedUsernameSubmitState.NotSent
            ) {
                log.warn("transparent username creation submit refused: an operation is {}", state)
                return false
            }
            _submitState.value = ShieldedUsernameSubmitState.Proving
        }
        scope.launch {
            val result = withContext(ioDispatcher) {
                createUsernameTransparent(username, secondaryUsername)
            }
            val outcome = when (result) {
                is SdkWriteResult.Broadcast -> ShieldedUsernameSubmitState.Created(result.value)
                is SdkWriteResult.NotBroadcast -> ShieldedUsernameSubmitState.NotSent(result.reason)
                is SdkWriteResult.Ambiguous -> ShieldedUsernameSubmitState.MayHaveGoneThrough
            }
            synchronized(this@SdkTransparentUsernameCreation) {
                _submitState.value = outcome
            }
        }
        return true
    }

    /**
     * The caller surfaced the result — Created/NotSent reset to Idle. The
     * funds-critical [ShieldedUsernameSubmitState.MayHaveGoneThrough] stays
     * sticky (never re-submittable this process); an in-flight Proving is
     * never cleared.
     */
    fun acknowledge() {
        synchronized(this) {
            when (_submitState.value) {
                ShieldedUsernameSubmitState.Proving,
                ShieldedUsernameSubmitState.MayHaveGoneThrough -> Unit
                else -> _submitState.value = ShieldedUsernameSubmitState.Idle
            }
        }
    }

    /**
     * The full pipeline. One funding attempt (resume-gated); the
     * [SdkWriteResult] three-valued contract holds
     * ([SdkWriteResult.Ambiguous] is never retried by anyone).
     */
    suspend fun createUsernameTransparent(
        username: String,
        secondaryUsername: String? = null
    ): SdkWriteResult<ShieldedUsernameCreationOutcome> {
        // Fail closed unless the cutover is committed — pre-cutover this path
        // must submit nothing (the dashj path owns funding then).
        val committed = try {
            cutoverCommitted()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("cutover state read failed", t)
        }
        if (!committed) return SdkWriteResult.NotBroadcast("cutover not committed")

        val label = username.trim()
        if (label.isEmpty()) {
            return notBroadcast("empty username", null)
        }
        val secondaryLabel = secondaryUsername?.trim()?.takeIf { it.isNotEmpty() && it != label }

        // Contested-ness is derived HERE from the labels (same rule the dashj
        // path uses — see TopUpRepository.createAssetLockTransaction) so the
        // asset lock is funded with the correct fee: contested labels take
        // DASH_PAY_FEE_CONTESTED (0.25), non-contested DASH_PAY_FEE (0.03).
        // Either label being contestable bumps the funding requirement.
        val contested = try {
            Names.isUsernameContestable(label) ||
                (secondaryLabel != null && Names.isUsernameContestable(secondaryLabel))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("contested-ness check failed", t)
        }
        val amountDuffs = try {
            feeDuffs(contested)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("username funding amount unavailable", t)
        }

        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast("app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("SDK bootstrap/bind lookup failed", t)
        }

        // Identity slot. Deterministic (DIP-9), so a re-entry at the same index
        // re-derives the SAME keys the resume will sign with.
        val identityIndex = try {
            source.managedIdentityCount(walletId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("identity slot lookup failed", t)
        }

        // MANDATORY RESUME GATE — runs BEFORE the key derivation AND the plain
        // funding call. If a killed prior attempt already built (and maybe
        // broadcast) an asset lock for THIS identity index, resume it
        // (re-consumes the SAME UTXOs); otherwise build fresh. Calling the plain
        // path while an unresolved lock exists would select DIFFERENT UTXOs =
        // DOUBLE-PAY. It runs first because it also picks the KEY SET: a fresh
        // build commits its keys for the first time and takes the six-key set
        // (with the DashPay ENCRYPTION/DECRYPTION pair), while a resume must not
        // grow past the four keys the killed attempt committed to.
        val existingLock = try {
            source.unresolvedRegistrationAssetLock(walletId, identityIndex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // A failed recovery lookup cannot prove no lock exists — refuse
            // the FRESH build rather than risk a double-pay. Retry-safe:
            // nothing was spent.
            return notBroadcast("resume-gate recovery lookup failed", t)
        }
        val includeDashPayKeys = existingLock == null

        // The canonical key set for that choice. previewRegistrationKeySet
        // returns the private scalars in hand (IdentityKeyPreview.privateKey)
        // precisely so the caller can store them for the signer — see the
        // persist step below.
        val keys = try {
            source.previewRegistrationKeySet(walletId, identityIndex, includeDashPayKeys)
                .also { check(it.isNotEmpty()) { "empty registration key set" } }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("registration key derivation failed", t)
        }

        // MANDATORY KEY PERSIST — each registration row's OWN private key into
        // the SDK's Keystore-backed WalletStorage BEFORE the funding call.
        // IdentityRegistration.registerWithWalletFunding signs the identity
        // create state transition with the KeystoreSigner, which resolves each
        // registration key's private half by LOOKUP (retrievePrivateKey by
        // pubkey hex) — identity keys are NEVER derived by the signer (only
        // 0xFF platform-address keys are). If a row's private key is not
        // already stored, the signer throws SigningKeyUnavailable ("no private
        // key stored for <pubkeyHex>"): the on-device create crash this path
        // hit. The SDK's own auto-persist deriver only fires during
        // post-create changeset commit (too late) and for sync/restore
        // discovery, not this create flow, so the caller must store the
        // preview's scalars here. Idempotent before BOTH the fresh-register and
        // the resume paths (the resume re-derives the SAME DIP-9 keys at the
        // same index). The DashPay ENCRYPTION/DECRYPTION rows of a fresh
        // six-key set go through the identical persist + zeroization — the
        // signer resolves them by the same lookup as every other row.
        // Zero the in-memory scalar right after — storePrivateKey has already
        // encrypted it into the same keystore the signer reads.
        try {
            keys.forEach { key ->
                source.storeIdentityPrivateKey(walletId, key.publicKeyHex, key.privateKey)
                key.privateKey.fill(0)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Nothing was spent — a persist failure is provably pre-broadcast
            // and retry-safe (the resume gate re-consumes any lock a retry finds).
            return notBroadcast("registration key persist failed", t)
        }

        // THE funding spend — one attempt (resume or fresh build).
        val identityId = try {
            if (existingLock != null) {
                log.info(
                    "resuming transparent identity funding at index {} from tracked asset lock (status={})",
                    identityIndex,
                    existingLock.status
                )
                source.resumeRegistrationFromAssetLock(walletId, existingLock, identityIndex, keys)
            } else {
                source.registerWithWalletFunding(
                    walletIdHex = walletId,
                    amountDuffs = amountDuffs,
                    accountIndex = ACCOUNT_INDEX,
                    identityIndex = identityIndex,
                    keys = keys
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return when (val classified = classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("transparent identity funding rejected pre-broadcast", t)
                    classified
                }
                else -> {
                    log.error(
                        "transparent identity funding outcome unconfirmed — the asset lock MAY be on " +
                            "chain; it stays durably tracked and the resume gate re-consumes it. Do NOT retry",
                        t
                    )
                    SdkWriteResult.Ambiguous(t)
                }
            }
        }
        val identityIdBase58 = Identifier.from(identityId).toString()
        log.info(
            "transparent-funded identity created at index {} ({}…) — {} duffs, contested={}",
            identityIndex,
            identityIdBase58.take(8),
            amountDuffs,
            contested
        )

        // Capture the funding asset-lock txid so the app-side records can label
        // this L1 asset lock as an "Upgrade" instead of the SDK's neutral
        // "Internal" classification (AssetLockKindResolver reads ASSET_LOCK_TXID).
        // The resume branch already holds the lock; the fresh branch re-queries
        // the recovery/resume-gate query (the lock is committed to the SDK store
        // at status Built before broadcast). Best-effort and post-success: a
        // create that cannot cleanly recover the txid still succeeds — the row
        // simply stays labelled "Internal". Persist the DISPLAY-hex (lowercase,
        // reversed-wire) form matching Sha256Hash.toString() / tx_display_cache.
        try {
            // The resume branch already holds the lock (wire-order txid → display).
            // The fresh branch's register CONSUMED the lock, so the eligible-only
            // recovery query now DROPS it — read the retaining `asset_locks` Room
            // table instead (already display form, no displayHexOf conversion).
            val lockTxidDisplayHex = existingLock?.let { displayHexOf(it.outpointTxid) }
                ?: source.registrationAssetLockTxidDisplayHex(walletId, identityIndex)
            if (lockTxidDisplayHex != null) {
                // Seed the kind in-memory FIRST (synchronous, same turn) so the
                // first feed classification already reads "Upgrade"; the
                // DataStore persist below is the durable backstop across
                // restarts. Same display-hex key the resolver reads from
                // ASSET_LOCK_TXID (Sha256Hash.toString() / tx_display_cache).
                seedAssetLockKind(lockTxidDisplayHex, AssetLockKind.UPGRADE)
                persistFundingTxid(lockTxidDisplayHex)
            } else {
                log.warn(
                    "identity funding at index {} succeeded but its asset-lock txid was not recoverable; " +
                        "the row stays labelled Internal",
                    identityIndex
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to capture/persist identity funding asset-lock txid", t)
        }

        // The identity is on chain. The DPNS name is a SEPARATE, REQUIRED
        // transition — persist the identity + labels first so the home tile
        // has context (and, on failure, a tile retry can re-drive ONLY the
        // DPNS step for THIS identity, never re-fund). restoring=false here so
        // the tile reads "requesting your username" while the name lands.
        try {
            persistNameContext(identityIdBase58, label, secondaryLabel, false)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to persist identity/name context before DPNS registration", t)
        }

        // REQUIRED, state-tracked DPNS registration (primary). Drives the
        // creation state AROUND source.registerDpnsName so the tile shows the
        // processing card while it lands, and GATES the terminal outcome on
        // the name — a failure is surfaced as a RETRYABLE error, never a false
        // success.
        val (nameStatus, nameFailure) = registerNameTracked(
            walletId,
            identityId,
            label,
            preorderState = IdentityCreationState.PREORDER_REGISTERING,
            registeringState = IdentityCreationState.USERNAME_REGISTERING,
            registeredState = IdentityCreationState.USERNAME_REGISTERED
        )
        // Secondary (dual-username flow) is only attempted once the primary
        // CONFIRMED — the secondary's own registering states drive the tile.
        val secondaryResult = if (nameStatus == ShieldedUsernameNameStatus.REGISTERED) {
            secondaryLabel?.let {
                registerNameTracked(
                    walletId,
                    identityId,
                    it,
                    preorderState = IdentityCreationState.PREORDER_SECONDARY_REGISTERING,
                    registeringState = IdentityCreationState.USERNAME_SECONDARY_REGISTERING,
                    registeredState = IdentityCreationState.USERNAME_SECONDARY_REGISTERED
                )
            }
        } else {
            null
        }

        val primaryConfirmed = nameStatus == ShieldedUsernameNameStatus.REGISTERED
        if (primaryConfirmed) {
            // Primary name CONFIRMED — hand the on-chain identity to the restore
            // state machine, which recovers it and advances to DONE (or VOTING
            // for a contested primary). DONE is ONLY ever reached with a
            // CONFIRMED primary name (never DONE + NOT_PRESENT). The secondary
            // (instant, dual-username flow) stays BEST-EFFORT: its status is
            // reported in the outcome, but a secondary-only failure must not
            // strand the confirmed primary in limbo. Gating the secondary's own
            // retry on this worker is not clean (the worker keys re-registration
            // on the PRIMARY's currentUsername, so once the primary confirms it
            // skips re-registration) — the secondary can be re-requested through
            // the normal instant-username flow if it did not land.
            // A NON-contested confirmed primary is TERMINAL right here: the
            // identity and its uncontested name are both on chain, so drive the
            // creation state to DONE now — independent of (and before) the
            // best-effort handoff — so `hasUsername` flips immediately and the
            // home welcome tile + DashPay bottom-nav appear without waiting for
            // RestoreIdentityWorker's full network recovery to write DONE later.
            // A CONTESTED primary must NOT be forced to DONE: it still routes
            // through the worker to VOTING, so leave its state untouched here.
            if (!contested) {
                try {
                    driveCreationState(IdentityCreationState.DONE, null)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.warn(
                        "failed to advance creation state to DONE for confirmed non-contested primary {}…; " +
                            "RestoreIdentityWorker recovery will still reach DONE",
                        identityIdBase58.take(8),
                        t
                    )
                }
            }
            // Best-effort handoff: a handoff failure must not demote a real
            // Broadcast (the identity/name are on chain; platform sync recovery
            // also picks it up). The handoff enqueues RestoreIdentityOperation
            // for profile/contact recovery (and, for a contested primary, the
            // VOTING advance).
            try {
                handOffToLegacy(identityIdBase58)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.error(
                    "legacy identity handoff failed for {}… — the identity/name are on chain; " +
                        "platform sync recovery will pick it up",
                    identityIdBase58.take(8),
                    t
                )
            }
        } else {
            // The PRIMARY DPNS name did NOT confirm (the secondary is never
            // attempted until the primary lands). The identity IS on chain, but
            // the username is not — registerNameTracked left the creation state
            // at USERNAME_REGISTERING. A GENUINE (NOT_REGISTERED, provably
            // pre-broadcast) rejection was stamped with the error so the tile
            // shows the RETRYABLE error card; an AMBIGUOUS (unconfirmed) outcome
            // was left non-terminal WITHOUT an error (self-healing: it will
            // reconcile on sync). Mark restoring so a tile retry — or the
            // background sync — routes to RestoreIdentityWorker, which re-drives
            // ONLY the DPNS step for the existing identity (never re-funds). Do
            // NOT hand off here (that would double-attempt the registration) and
            // do NOT advance to DONE.
            try {
                persistNameContext(identityIdBase58, label, secondaryLabel, true)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to mark identity restoring after DPNS registration failure", t)
            }
            log.warn(
                "transparent identity {}… created but its DPNS name did not register " +
                    "(primary={}, secondary={}); a tile retry re-drives ONLY the name step",
                identityIdBase58.take(8),
                nameStatus,
                secondaryResult?.first
            )
        }

        // The identity IS on chain (the funding spend confirmed) either way —
        // this is a Broadcast. nameStatus / secondaryNameStatus qualify whether
        // each username landed.
        return SdkWriteResult.Broadcast(
            ShieldedUsernameCreationOutcome(
                identityIdBase58 = identityIdBase58,
                nameStatus = nameStatus,
                nameFailureReason = nameFailure,
                secondaryNameStatus = secondaryResult?.first,
                secondaryNameFailureReason = secondaryResult?.second
            )
        )
    }

    /**
     * One REQUIRED, state-tracked DPNS registration for an identity that
     * already exists on chain. Drives the creation state around
     * `source.registerDpnsName` ([preorderState] → [registeringState], then
     * [registeredState] on success) so the home tile reflects the step, and on
     * failure LEAVES the state at [registeringState] stamped with the error so
     * the tile shows the retryable error card. The [classifyBroadcastFailure]
     * contract holds per name (a provably pre-broadcast rejection is
     * NOT_REGISTERED, anything unconfirmed is AMBIGUOUS); both are
     * retry-safe-without-refund because a retry re-drives ONLY the DPNS step
     * against the same existing identity (the restore worker even re-checks the
     * on-chain name before re-registering, so an AMBIGUOUS name that actually
     * landed is not double-registered).
     */
    private suspend fun registerNameTracked(
        walletId: String,
        identityId: ByteArray,
        label: String,
        preorderState: IdentityCreationState,
        registeringState: IdentityCreationState,
        registeredState: IdentityCreationState
    ): Pair<ShieldedUsernameNameStatus, String?> {
        try {
            driveCreationState(preorderState, null)
            driveCreationState(registeringState, null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to record DPNS registering state for '{}'", label, t)
        }
        return try {
            source.registerDpnsName(walletId, identityId, label)
            try {
                driveCreationState(registeredState, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to record DPNS registered state for '{}'", label, t)
            }
            ShieldedUsernameNameStatus.REGISTERED to null
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val (status, reason) = when (classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("DPNS registration of '{}' rejected pre-broadcast after identity creation", label, t)
                    ShieldedUsernameNameStatus.NOT_REGISTERED to (t.message ?: "name registration failed")
                }
                else -> {
                    log.error("DPNS registration of '{}' outcome unconfirmed after identity creation", label, t)
                    ShieldedUsernameNameStatus.AMBIGUOUS to (t.message ?: "name registration ambiguous")
                }
            }
            // Leave the creation state at the registering state. A provably
            // pre-broadcast rejection (NOT_REGISTERED) is a GENUINE, terminal
            // failure for this attempt — stamp the error so the home tile shows
            // the retryable error card. An AMBIGUOUS (unconfirmed) outcome is
            // the self-healing case: the identity is on chain and the restore
            // worker re-checks the on-chain name before re-registering (never a
            // double-register, never a re-fund), so DON'T stamp a hard error —
            // keep the sticky non-terminal "unconfirmed, will reconcile on sync"
            // registering state WITHOUT a creationStateErrorMessage, mirroring
            // the executor's funds-critical ShieldedUsernameSubmitState.MayHaveGoneThrough,
            // which likewise stays sticky without surfacing a hard error. The
            // ambiguous reason is still returned in the outcome for logging.
            val errorForTile = if (status == ShieldedUsernameNameStatus.AMBIGUOUS) null else reason
            try {
                driveCreationState(registeringState, errorForTile)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                log.warn("failed to stamp DPNS registration state for '{}'", label, e)
            }
            status to reason
        }
    }

    /**
     * Re-drive ONLY the DPNS registration for an ALREADY-CREATED identity
     * ([identityIdBase58], the on-chain identity id). Used by the completion /
     * retry engine ([de.schildbach.wallet.service.platform.work.RestoreIdentityWorker])
     * to land — or re-land — the username WITHOUT any funding: the identity
     * already exists, so there is no asset lock, no double-pay, no re-fund.
     * Returns [SdkWriteResult.Broadcast] with the full domain name on success;
     * [SdkWriteResult.NotBroadcast] when nothing was submitted (SDK wallet not
     * bound / malformed id / provably pre-broadcast rejection — retry-safe); and
     * [SdkWriteResult.Ambiguous] when the outcome cannot be proven pre-broadcast
     * (the caller re-checks the on-chain name before re-registering).
     */
    suspend fun registerDpnsNameForExistingIdentity(
        identityIdBase58: String,
        label: String
    ): SdkWriteResult<String> {
        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return SdkWriteResult.NotBroadcast("app wallet not bound to the SDK")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return SdkWriteResult.NotBroadcast("SDK bootstrap/bind lookup failed", t)
        }
        val identityId = try {
            Identifier.from(identityIdBase58).toBuffer()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return SdkWriteResult.NotBroadcast("malformed identity id", t)
        }
        return try {
            val fullName = source.registerDpnsName(walletId, identityId, label)
            SdkWriteResult.Broadcast(fullName)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            classifyBroadcastFailure(t)
        }
    }

    private fun notBroadcast(reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("transparent username creation not attempted ({})", reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkTransparentUsernameCreation::class.java)

        /** BIP44 account the identity asset lock is funded from (same as the SDK's plain send). */
        private const val ACCOUNT_INDEX = 0
    }
}
