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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identity-registration funding amount in Core DUFFS for a transparent
 * username of the given contested-ness — the exact lambda
 * [SdkTransparentUsernameCreation]'s `@Inject` constructor wires as
 * `feeDuffs`, extracted so the protocol-gating (MO-1069) is host-JVM
 * unit-testable without standing up the rest of the SDK plumbing. Contested
 * labels resolve through [ContestedUsernameFees.resolved] (a live
 * [DashSdkService.currentProtocolVersion] read: 0.25 DASH pre-v4.2, 0.15 DASH
 * once the network reports protocol >= 14, and [ProtocolVersionUnavailableException]
 * when the version is unknown — funding never falls back to a fee); non-contested
 * labels are the fixed `DASH_PAY_FEE` (0.03 DASH).
 */
internal suspend fun transparentUsernameFeeDuffs(contested: Boolean, sdkService: DashSdkService): Long =
    if (contested) {
        ContestedUsernameFees.resolved(sdkService).contested.value
    } else {
        Constants.DASH_PAY_FEE.value
    }

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
/**
 * True when [identityIdBase58] already owns [label] or contends for it (a contested name in
 * voting). Throws when Platform cannot be read: callers fail closed.
 */
internal suspend fun identityOwnsOrContendsFor(
    repo: de.schildbach.wallet.ui.dashpay.PlatformRepo,
    identityIdBase58: String,
    label: String
): Boolean {
    val normalized = Names.normalizeString(label)
    return repo.getOwnedUsernamesOrThrow(identityIdBase58).any { Names.normalizeString(it) == normalized } ||
        (
            Names.isUsernameContestable(label) &&
                repo.getVoteContendersOrThrow(label).map.keys.any { it == Identifier.from(identityIdBase58) }
            )
}

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

    /**
     * FEE-LEDGER read: the LOCKED amount (Core duffs) of the
     * IDENTITY_REGISTRATION asset lock the SDK persisted for [identityIndex],
     * or null when no row is found. Same retaining `asset_locks` Room table as
     * [registrationAssetLockTxidDisplayHex] — covers a RESUMED lock too, whose
     * [TrackedAssetLock] carries no amount. Local read; diagnostics only.
     */
    suspend fun registrationAssetLockAmountDuffs(walletIdHex: String, identityIndex: Int): Long?

    /**
     * FEE-LEDGER read: [identityIdBase58]'s current Platform credit balance,
     * or null when the identity is not found. One DAPI round trip that may
     * lag a just-applied transition (see [IdentityFeeLedger]); diagnostics
     * only — callers bound it and ignore failures.
     */
    suspend fun identityCreditBalance(identityIdBase58: String): Long?
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

    override suspend fun registrationAssetLockAmountDuffs(walletIdHex: String, identityIndex: Int): Long? =
        service.databaseOrNull()?.assetLockDao()
            ?.observeByWalletAndIdentityIndex(walletId(walletIdHex), identityIndex)
            ?.first()
            ?.firstOrNull {
                it.fundingTypeRaw == TrackedAssetLock.FundingType.IDENTITY_REGISTRATION.raw
            }
            ?.amountDuffs

    override suspend fun identityCreditBalance(identityIdBase58: String): Long? {
        service.ensureStarted()
        val sdk = checkNotNull(service.sdkOrNull()) { "SDK runtime missing after ensureStarted()" }
        return sdk.identities.fetchBalance(identityIdBase58)
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
     * contested-ness — [ContestedUsernameFees.resolved]'s protocol-gated
     * contested fee for contested labels, `DASH_PAY_FEE` = 0.03 DASH
     * otherwise — the same fee the dashj path funds the asset lock with
     * ([de.schildbach.wallet.service.platform.TopUpRepository.createAssetLockTransaction]).
     * Suspend: resolving the contested fee needs a live protocol-version read
     * (MO-1069).
     */
    private val feeDuffs: suspend (contested: Boolean) -> Long,
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
    private val executorScope: CoroutineScope? = null,
    /** Back-off between false-locked auto-resume attempts; injectable for tests. */
    private val pause: suspend (delayMs: Long) -> Unit = { delay(it) },
    /**
     * Reset Wallet's fence over old-wallet secret writes: the key persist and
     * the funding/resume calls run under its permit, and [submit]'s job is
     * registered so the wipe can cancel and join it. A fresh (lowered) fence
     * by default keeps the host-JVM tests unaffected.
     */
    private val secretWriteFence: WalletSecretWriteFence = WalletSecretWriteFence(),
    /**
     * True while an uncertain BIP70 payment's inputs are exposed to the engine's coin selection
     * ([de.schildbach.wallet.payments.PendingPaymentFundingGate]); a FRESH registration build is
     * then refused with [L1_PENDING_PAYMENT_UNPROTECTED_REASON]. Resuming a tracked lock re-consumes
     * that lock's own outputs and is never asked. Default false keeps the host-JVM tests inert.
     */
    private val freshFundingBlocked: suspend () -> Boolean = { false },
    /** Shared with a resubmission's reactivation; see [de.schildbach.wallet.payments.FundingSelectionLock]. */
    private val fundingSelection: de.schildbach.wallet.payments.FundingSelectionLock,
    /** Drops the old name request's lost or locked status before a name-only retry. No-op by default. */
    private val clearPreviousNameRequest: suspend () -> Unit = {},
    /**
     * True when the identity already owns [label], or contends for it: a name-only retry never
     * submits it again. Throws when that cannot be read, refusing the retry (fail closed).
     */
    private val nameAlreadyTaken: suspend (identityIdBase58: String, label: String) -> Boolean = { _, _ -> false },
    /** [cutoverCommitted] as a tri-state, null when the state cannot be read. Defaults to [cutoverCommitted]. */
    private val cutoverCommittedOrUnknown: suspend () -> Boolean? = { cutoverCommitted() },
    /**
     * Held across every DPNS registration's re-check → submit → record, per identity, shared with
     * the other in-app registrars. A fresh lock by default keeps the host-JVM tests independent.
     */
    private val dpnsLock: DpnsRegistrationLock = DpnsRegistrationLock()
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        sdkL1SendService: SdkL1SendService,
        walletApplication: WalletApplication,
        blockchainIdentityConfig: BlockchainIdentityConfig,
        assetLockKindResolver: AssetLockKindResolver,
        applicationScope: CoroutineScope,
        secretWriteFence: WalletSecretWriteFence,
        fundingGate: de.schildbach.wallet.payments.PendingPaymentFundingGate,
        dpnsLock: DpnsRegistrationLock,
        // A Provider: only a name-only retry reads it, and it pulls in the platform services.
        platformRepo: javax.inject.Provider<de.schildbach.wallet.ui.dashpay.PlatformRepo>
    ) : this(
        source = DashSdkTransparentUsernameSource(sdkService),
        cutoverCommitted = { sdkL1SendService.cutoverCommitted() },
        cutoverCommittedOrUnknown = { sdkL1SendService.cutoverCommittedOrUnknown() },
        // Lazy: Constants untouched at construction (inert-until-called).
        feeDuffs = { contested -> transparentUsernameFeeDuffs(contested, sdkService) },
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
        executorScope = applicationScope,
        secretWriteFence = secretWriteFence,
        freshFundingBlocked = { fundingGate.isFreshFundingBlocked() },
        fundingSelection = fundingGate.selectionLock,
        clearPreviousNameRequest = {
            // the lost or locked status belonged to the old name, not to this request
            blockchainIdentityConfig.remove(BlockchainIdentityConfig.USERNAME_REQUESTED)
        },
        nameAlreadyTaken = { identityIdBase58, label ->
            identityOwnsOrContendsFor(platformRepo.get(), identityIdBase58, label)
        },
        dpnsLock = dpnsLock
    )

    /** Test seam: the funding call blocks on network I/O — keep it off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Test seam: runs between the fence registration of a submit or retry job and its start. */
    internal var beforeStartForTest: () -> Unit = {}

    /** Test seam: per-read budget of the log-only fee-ledger balance reads. */
    internal var feeLedgerReadTimeoutMs: Long = IdentityFeeLedger.BALANCE_READ_TIMEOUT_MS

    private val _submitState =
        MutableStateFlow<ShieldedUsernameSubmitState>(ShieldedUsernameSubmitState.Idle)

    /** The in-flight/terminal state of the one allowed creation operation. */
    val submitState: StateFlow<ShieldedUsernameSubmitState> = _submitState.asStateFlow()

    /** A name-only retry ([retryNameForExistingIdentity]) is running; guarded by `synchronized(this)`. */
    private var nameRetryInFlight = false

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
     * Whether the cutover is committed, or null when the state cannot be read (review
     * 4299080469). For a routing decision where "not committed" starts the dashj identity path,
     * which resets a registered identity or funds through the held engine: unknown must refuse.
     */
    suspend fun cutoverStateOrUnknown(): Boolean? = try {
        cutoverCommittedOrUnknown()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("failed to read the cutover state; it is unknown", e)
        null
    }

    /**
     * Start the creation on the application scope. Returns false — and
     * submits NOTHING — unless the current state is Idle or the
     * provably-pre-broadcast NotSent (retry-safe). The Idle→Proving
     * transition is atomic under [this]. Also refused while Reset Wallet holds
     * the [secretWriteFence]; the job is registered with that fence, which
     * cancels and joins it when a wipe starts.
     */
    fun submit(username: String, secondaryUsername: String? = null): Boolean {
        val scope = executorScope
        if (scope == null) {
            log.warn("transparent username creation submit refused: no executor scope")
            return false
        }
        if (secretWriteFence.isRaised) {
            log.warn("transparent username creation submit refused: wallet wipe in progress")
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
            if (nameRetryInFlight) {
                log.warn("transparent username creation submit refused: a name-only retry is running")
                return false
            }
            _submitState.value = ShieldedUsernameSubmitState.Proving
        }
        // LAZY + tracked before start: the job can never run unfenced.
        val job = scope.launch(start = CoroutineStart.LAZY) {
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
        job.invokeOnCompletion { cause -> if (cause is CancellationException) onCancelled() }
        if (!trackOrRefuse(job)) {
            // the cancelled lazy job never ran; [onCancelled] already released the slot
            log.warn("transparent username creation submit refused: wallet wipe in progress")
            return false
        }
        if (!startOrRefuse(job)) {
            // a wipe cancelled it after the track; [onCancelled] frees the slot at its completion
            log.warn("transparent username creation submit refused: wallet wipe in progress")
            return false
        }
        return true
    }

    /**
     * Start the tracked LAZY [job]. False when a wipe cancelled it after [trackOrRefuse] and before
     * this start: the job never ran (or is being cancelled), and the caller must report a refusal.
     */
    private fun startOrRefuse(job: Job): Boolean {
        beforeStartForTest()
        return job.start() && !job.isCancelled
    }

    /**
     * Register the LAZY [job] with the [secretWriteFence]. False — with the job cancelled, so it
     * can never run — when a wipe raised the fence after the caller's early check: [track]
     * cancelled it at once, or the fence went up just after. The caller must then report a
     * refusal, not an accepted run.
     */
    private fun trackOrRefuse(job: Job): Boolean {
        secretWriteFence.track(job)
        if (job.isCancelled || secretWriteFence.isRaised) {
            job.cancel(CancellationException("wallet wipe in progress"))
            return false
        }
        return true
    }

    /**
     * The wipe cancelled the run (the only canceller). In-memory only: the
     * single-flight slot is released so the NEXT wallet can submit in this
     * process; nothing persisted is touched (the wipe clears the identity
     * config, and the SDK still tracks any lock the run built).
     */
    private fun onCancelled() {
        synchronized(this) {
            if (_submitState.value == ShieldedUsernameSubmitState.Proving) {
                log.warn("transparent username creation cancelled (wallet wipe) — released the submit slot")
                _submitState.value = ShieldedUsernameSubmitState.Idle
            }
        }
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
     * The full pipeline. One funding attempt (resume-gated) — plus, only
     * after a FALSE-LOCKED Keystore signing refusal with the asset lock
     * already tracked, at most [AUTO_RESUME_DELAYS_MS].size RESUMES of that
     * same lock (never a second fresh build); the [SdkWriteResult]
     * three-valued contract holds ([SdkWriteResult.Ambiguous] is never
     * retried by anyone).
     */
    /** [freshFundingBlocked], failing closed. */
    private suspend fun isFreshFundingBlockedSafe(): Boolean = try {
        freshFundingBlocked()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("could not check for unprotected pending payments; refusing the fresh build", t)
        true
    }

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
        if (secretWriteFence.isRaised) return SdkWriteResult.NotBroadcast(REASON_WALLET_WIPE_IN_PROGRESS)

        val label = username.trim()
        if (label.isEmpty()) {
            return notBroadcast("empty username", null)
        }
        val secondaryLabel = secondaryUsername?.trim()?.takeIf { it.isNotEmpty() && it != label }

        // Contested-ness is derived HERE from the labels (same rule the dashj
        // path uses — see TopUpRepository.createAssetLockTransaction) so the
        // asset lock is funded with the correct fee: contested labels take
        // the protocol-gated contested fee (ContestedUsernameFees), non-contested
        // DASH_PAY_FEE (0.03). Either label being contestable bumps the funding
        // requirement.
        val contested = try {
            Names.isUsernameContestable(label) ||
                (secondaryLabel != null && Names.isUsernameContestable(secondaryLabel))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("contested-ness check failed", t)
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
        // A fresh build selects new inputs; refuse it (before any key is derived) while an
        // uncertain payment's inputs are exposed. Nothing is spent, so it is retry-safe.
        if (existingLock == null && isFreshFundingBlockedSafe()) {
            return SdkWriteResult.NotBroadcast(L1_PENDING_PAYMENT_UNPROTECTED_REASON)
        }
        val includeDashPayKeys = existingLock == null

        // The funding quote is needed ONLY to build fresh funding. Resolving it before the
        // recovery lookup made a transient protocol-version failure block the resume of an
        // asset lock a previous attempt already built or broadcast — committed funds that need
        // no new quote. So: inspect the recovery record first, resolve strictly only for the
        // fresh arm (pre-broadcast refusal retained there).
        // Null on the resume arm: no fresh amount is resolved, and a placeholder
        // would leak into the success log and the fee ledger as a 0-duff funding.
        val amountDuffs: Long? = if (existingLock != null) {
            null
        } else {
            try {
                feeDuffs(contested)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return notBroadcast("username funding amount unavailable", t)
            }
        }

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
        //
        // WIPE FENCE: the persist AND the funding (whose SDK commit persists
        // keys too) run under one permit, so a Reset Wallet either refuses
        // them up front or waits for them before it verifies/erases the SDK
        // secrets store — no old-wallet key can land after that erase.
        if (!secretWriteFence.tryAcquire("transparent identity key persist + funding")) {
            keys.forEach { it.privateKey.fill(0) }
            return SdkWriteResult.NotBroadcast(REASON_WALLET_WIPE_IN_PROGRESS)
        }
        // [fundingLock] is the lock the identity was finally funded from
        // (null = the fresh build's own).
        var fundingLock = existingLock
        val identityId = try {
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

            // A wipe raised the fence while the keys were persisted: spend
            // nothing for a wallet that is being destroyed.
            if (secretWriteFence.isRaised) return SdkWriteResult.NotBroadcast(REASON_WALLET_WIPE_IN_PROGRESS)

            // THE funding spend — one attempt (resume or fresh build), plus the
            // bounded false-locked auto-resume below.
            try {
                if (existingLock != null) {
                    log.info(
                        "resuming transparent identity funding at index {} from tracked asset lock (status={})",
                        identityIndex,
                        existingLock.status
                    )
                    source.resumeRegistrationFromAssetLock(walletId, existingLock, identityIndex, keys)
                } else {
                    // Asked again under the shared selection lock and held through the build
                    // (review 4309551218): a resubmission's reactivation cannot slip in between.
                    fundingSelection.selectIfAllowed({ isFreshFundingBlockedSafe() }) {
                        source.registerWithWalletFunding(
                            walletIdHex = walletId,
                            // Set whenever existingLock is null (see above).
                            amountDuffs = checkNotNull(amountDuffs) { "fresh funding amount not resolved" },
                            accountIndex = ACCOUNT_INDEX,
                            identityIndex = identityIndex,
                            keys = keys
                        )
                    }?.value ?: return SdkWriteResult.NotBroadcast(L1_PENDING_PAYMENT_UNPROTECTED_REASON)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // A Keystore SIGNING refusal is pre-broadcast for the Platform
                // transition, but the SDK builds AND BROADCASTS the L1 asset lock
                // BEFORE it signs IdentityCreate — so "nothing spent" is only true
                // when no lock is tracked. MO-1023: on a false-locked device the
                // lock was on chain (instant-locked) while this reported NotSent and
                // retracted the tile; a manual retry then always resumed it.
                val recovered = if (isKeystoreSigningRefusal(t)) {
                    recoverFromKeystoreRefusal(walletId, identityIndex, t, knownLock = existingLock)
                } else {
                    null
                }
                when (recovered) {
                    is KeystoreRefusalRecovery.Resumed -> {
                        fundingLock = recovered.lock
                        recovered.identityId
                    }
                    is KeystoreRefusalRecovery.Failed -> return recovered.result
                    null -> return when (val classified = classifyBroadcastFailure(t)) {
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
            }
        } finally {
            secretWriteFence.release()
        }

        val identityIdBase58 = Identifier.from(identityId).toString()
        log.info(
            "transparent-funded identity created at index {} ({}…) — {}, contested={}",
            identityIndex,
            identityIdBase58.take(8),
            amountDuffs?.let { "$it duffs" } ?: "resumed from a tracked asset lock",
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
            // The resume branch (incl. a false-locked auto-resume) already holds
            // the lock (wire-order txid → display).
            // The fresh branch's register CONSUMED the lock, so the eligible-only
            // recovery query now DROPS it — read the retaining `asset_locks` Room
            // table instead (already display form, no displayHexOf conversion).
            val lockTxidDisplayHex = fundingLock?.let { displayHexOf(it.outpointTxid) }
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

        // FEE LEDGER (log-only, best-effort): credited vs post-create balance
        // = the IdentityCreate fee. The post-create balance doubles as the
        // primary DPNS step's "before" snapshot.
        val balanceAfterCreate = recordIdentityCreateFee(walletId, identityIndex, identityIdBase58, amountDuffs)

        return registerNamesAndHandOff(
            walletId,
            identityId,
            identityIdBase58,
            label,
            secondaryLabel,
            contested,
            balanceAfterCreate
        )
    }

    /**
     * The name tail once the identity is on chain, shared by [createUsernameTransparent] and the
     * name-only retry: names in [alreadyTaken] are not submitted again (see [registerNameTracked]),
     * and a name-only retry re-reads each name's ownership right before submitting it.
     */
    private suspend fun registerNamesAndHandOff(
        walletId: String,
        identityId: ByteArray,
        identityIdBase58: String,
        label: String,
        secondaryLabel: String?,
        contested: Boolean,
        balanceBefore: Long?,
        alreadyTaken: Set<String> = emptySet(),
        // a name-only retry: on failure, its own retryable error, never the restore route
        nameOnlyRetry: Boolean = false
    ): SdkWriteResult.Broadcast<ShieldedUsernameCreationOutcome> {
        // REQUIRED, state-tracked DPNS registration (primary). Drives the
        // creation state AROUND source.registerDpnsName so the tile shows the
        // processing card while it lands, and GATES the terminal outcome on
        // the name — a failure is surfaced as a RETRYABLE error, never a false
        // success.
        val primary = registerNameTracked(
            walletId,
            identityId,
            identityIdBase58,
            label,
            balanceBefore = balanceBefore,
            preorderState = IdentityCreationState.PREORDER_REGISTERING,
            registeringState = IdentityCreationState.USERNAME_REGISTERING,
            registeredState = IdentityCreationState.USERNAME_REGISTERED,
            taken = label in alreadyTaken,
            recheckOwnership = nameOnlyRetry
        )
        val nameStatus = primary.status
        val nameFailure = primary.failureReason
        // Secondary (dual-username flow) is only attempted once the primary
        // CONFIRMED — the secondary's own registering states drive the tile.
        val secondaryResult = if (nameStatus == ShieldedUsernameNameStatus.REGISTERED) {
            secondaryLabel?.let {
                registerNameTracked(
                    walletId,
                    identityId,
                    identityIdBase58,
                    it,
                    balanceBefore = primary.balanceAfter,
                    preorderState = IdentityCreationState.PREORDER_SECONDARY_REGISTERING,
                    registeringState = IdentityCreationState.USERNAME_SECONDARY_REGISTERING,
                    registeredState = IdentityCreationState.USERNAME_SECONDARY_REGISTERED,
                    taken = it in alreadyTaken,
                    recheckOwnership = nameOnlyRetry
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
            //
            // A name-only retry does NOT take that route (review 4301924854): the restore worker
            // rebuilds the record from the chain, adopts the registered secondary as the name and
            // drops the replacement. It stamps its own retryable error instead, AMBIGUOUS
            // included, which the home tile sends back into the name-only retry; there the
            // ownership check keeps a name that did land from being submitted again.
            try {
                if (nameOnlyRetry) {
                    driveCreationState(
                        IdentityCreationState.USERNAME_REGISTERING,
                        "$NAME_ONLY_RETRY_ERROR: ${nameFailure ?: nameStatus.name}"
                    )
                } else {
                    persistNameContext(identityIdBase58, label, secondaryLabel, true)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to record the DPNS registration failure", t)
            }
            log.warn(
                "transparent identity {}… created but its DPNS name did not register " +
                    "(primary={}, secondary={}); a tile retry re-drives ONLY the name step",
                identityIdBase58.take(8),
                nameStatus,
                secondaryResult?.status
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
                secondaryNameStatus = secondaryResult?.status,
                secondaryNameFailureReason = secondaryResult?.failureReason
            )
        )
    }

    /** Outcome of [recoverFromKeystoreRefusal]. */
    private sealed class KeystoreRefusalRecovery {
        /** An automatic resume funded the identity from [lock]. */
        class Resumed(val identityId: ByteArray, val lock: TrackedAssetLock) : KeystoreRefusalRecovery()

        /** No automatic resume succeeded; [result] is the terminal outcome to return. */
        class Failed(val result: SdkWriteResult<Nothing>) : KeystoreRefusalRecovery()
    }

    /** Result of the tracked-lock re-check in [recoverFromKeystoreRefusal]. */
    private sealed class TrackedLockRecheck {
        class LockFound(val lock: TrackedAssetLock) : TrackedLockRecheck()

        /** The lookup SUCCEEDED and no lock is tracked — the refusal was provably pre-broadcast. */
        object NoLock : TrackedLockRecheck()

        /** The lookup THREW — it establishes nothing about whether a lock was broadcast. */
        class LookupFailed(val error: Throwable) : TrackedLockRecheck()
    }

    /**
     * The funding call ([failure]) was refused by the Android Keystore signer
     * ([isKeystoreSigningRefusal]). Returns null only when the tracked-lock
     * re-check SUCCEEDED and found NO asset lock for [identityIndex] — the
     * refusal truly was pre-broadcast and the caller keeps today's
     * classification.
     *
     * When the re-check itself FAILS, nothing is proven: the SDK builds and
     * broadcasts the L1 asset lock before it signs IdentityCreate, so the
     * funds MAY be committed (the MO-1023 sequence). That is never classified
     * as a pre-broadcast refusal: if the failing call was a resume of
     * [knownLock] the funds are known committed ([REASON_FUNDS_LOCKED_RESUMABLE]);
     * otherwise the result is [SdkWriteResult.NotBroadcast] with
     * [REASON_FUNDING_UNRESOLVED_RESUMABLE] — re-submittable exactly like the
     * locked case, because the next submit's resume gate re-checks the
     * tracked lock (and refuses a fresh build while its lookup fails), so it
     * either resumes the committed lock or proves none exists — never a
     * second payment.
     *
     * When a lock IS tracked, the funds are committed on L1 and a retry must
     * RESUME that lock (the resume gate's own contract — never a second fresh
     * build). For the FALSE-LOCKED class ([isKeystoreFalseLockedRefusal]) the
     * SDK has by now recorded the Keystore defect, so re-storing the keys puts
     * them under the never-lock-bound alias and a resume signs fine: this
     * re-runs the key persist + [TransparentUsernameSource.resumeRegistrationFromAssetLock]
     * up to [AUTO_RESUME_DELAYS_MS].size times. A genuinely locked device
     * (or a bare "User not authenticated") is not spun on — it cannot succeed
     * until the user unlocks.
     *
     * If no automatic resume lands, the result is
     * [SdkWriteResult.NotBroadcast] with [REASON_FUNDS_LOCKED_RESUMABLE]
     * (unless a resume attempt itself failed ambiguously — then
     * [SdkWriteResult.Ambiguous]). NotBroadcast is deliberate: it is the only
     * outcome the executor + UI treat as re-submittable (NotSent), and the
     * NEXT submit is guaranteed to take the resume gate with this same lock,
     * so it cannot double-pay; the requested username stays persisted (the
     * foreground hold only resets the creation state to NONE, which is what
     * re-enables the Create entry point). Ambiguous would instead pin the
     * executor at MayHaveGoneThrough and block the resume until a restart.
     */
    private suspend fun recoverFromKeystoreRefusal(
        walletId: String,
        identityIndex: Int,
        failure: Throwable,
        knownLock: TrackedAssetLock?
    ): KeystoreRefusalRecovery? {
        val recheck = try {
            source.unresolvedRegistrationAssetLock(walletId, identityIndex)
                ?.let { TrackedLockRecheck.LockFound(it) }
                ?: TrackedLockRecheck.NoLock
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            TrackedLockRecheck.LookupFailed(t)
        }
        val lock = when (recheck) {
            is TrackedLockRecheck.LockFound -> recheck.lock
            TrackedLockRecheck.NoLock -> {
                log.info(
                    "Keystore refused the identity signing key at index {} and no asset lock is tracked — " +
                        "the refusal was pre-broadcast",
                    identityIndex
                )
                return null
            }
            is TrackedLockRecheck.LookupFailed -> {
                if (recheck.error !== failure) failure.addSuppressed(recheck.error)
                if (knownLock != null) {
                    log.warn(
                        "Keystore refused the identity signing key at index {} while resuming the tracked asset " +
                            "lock (status={}), and the tracked-lock re-check failed; the funds are committed and " +
                            "the next submit resumes it",
                        identityIndex,
                        knownLock.status,
                        recheck.error
                    )
                    return KeystoreRefusalRecovery.Failed(lockedResumable(failure))
                }
                log.warn(
                    "Keystore refused the identity signing key at index {}, and the tracked-lock re-check failed — " +
                        "the asset lock MAY already be broadcast (funding unresolved); the next submit's resume " +
                        "gate verifies/resumes it",
                    identityIndex,
                    recheck.error
                )
                return KeystoreRefusalRecovery.Failed(
                    SdkWriteResult.NotBroadcast(REASON_FUNDING_UNRESOLVED_RESUMABLE, failure)
                )
            }
        }
        if (!isKeystoreFalseLockedRefusal(failure)) {
            log.warn(
                "Keystore refused the identity signing key at index {} (not false-locked; no auto-resume) " +
                    "AFTER the asset lock was built (status={}); the next submit resumes it",
                identityIndex,
                lock.status,
                failure
            )
            return KeystoreRefusalRecovery.Failed(lockedResumable(failure))
        }
        log.warn(
            "Keystore false-locked refusal at index {} AFTER the asset lock was built (status={}); " +
                "auto-resuming from the tracked lock",
            identityIndex,
            lock.status,
            failure
        )
        var lastFailure = failure
        for ((attempt, delayMs) in AUTO_RESUME_DELAYS_MS.withIndex()) {
            pause(delayMs)
            // The lock is already built (maybe broadcast): a wipe that started
            // during the back-off just stops the resume — no key re-persist,
            // no resume call — and the lock stays tracked by the SDK.
            stopResumeForWipe(attempt, identityIndex)?.let { return it }
            log.info(
                "auto-resume {}/{} of transparent identity funding at index {} from tracked asset lock (status={})",
                attempt + 1,
                AUTO_RESUME_DELAYS_MS.size,
                identityIndex,
                lock.status
            )
            // Re-persist with the RESUME key set (4 keys — a resume must never
            // grow past the set the original attempt committed to). Now that
            // the SDK recorded the defect, these land under the unbound alias.
            val keys = try {
                source.previewRegistrationKeySet(walletId, identityIndex, includeDashPayKeys = false)
                    .also { check(it.isNotEmpty()) { "empty registration key set" } }
                    .onEach { key ->
                        source.storeIdentityPrivateKey(walletId, key.publicKeyHex, key.privateKey)
                        key.privateKey.fill(0)
                    }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Nothing new was submitted — the lock simply stays tracked.
                log.warn("auto-resume {} at index {}: key re-persist failed; stopping", attempt + 1, identityIndex, t)
                return KeystoreRefusalRecovery.Failed(lockedResumable(t))
            }
            stopResumeForWipe(attempt, identityIndex)?.let { return it }
            try {
                val identityId = source.resumeRegistrationFromAssetLock(walletId, lock, identityIndex, keys)
                log.info("auto-resume {} at index {} succeeded", attempt + 1, identityIndex)
                return KeystoreRefusalRecovery.Resumed(identityId, lock)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastFailure = t
                if (isKeystoreFalseLockedRefusal(t)) {
                    log.warn("auto-resume {} at index {} refused false-locked again", attempt + 1, identityIndex, t)
                    continue
                }
                if (classifyBroadcastFailure(t) is SdkWriteResult.Ambiguous) {
                    log.error(
                        "auto-resume {} at index {} outcome unconfirmed — the identity create MAY have been " +
                            "submitted; the lock stays tracked and the resume gate re-consumes it. Do NOT retry",
                        attempt + 1,
                        identityIndex,
                        t
                    )
                    return KeystoreRefusalRecovery.Failed(SdkWriteResult.Ambiguous(t))
                }
                log.warn("auto-resume {} at index {} failed pre-broadcast; stopping", attempt + 1, identityIndex, t)
                return KeystoreRefusalRecovery.Failed(lockedResumable(t))
            }
        }
        log.warn(
            "auto-resume exhausted at index {}: the asset lock (status={}) stays tracked; the next submit resumes it",
            identityIndex,
            lock.status
        )
        return KeystoreRefusalRecovery.Failed(lockedResumable(lastFailure))
    }

    private fun stopResumeForWipe(attempt: Int, identityIndex: Int): KeystoreRefusalRecovery.Failed? {
        if (!secretWriteFence.isRaised) return null
        log.warn(
            "auto-resume {} at index {} stopped: wallet wipe in progress; the asset lock stays tracked",
            attempt + 1,
            identityIndex
        )
        return KeystoreRefusalRecovery.Failed(lockedResumable(WalletWipeInProgressException("identity funding resume")))
    }

    private fun lockedResumable(cause: Throwable) =
        SdkWriteResult.NotBroadcast(REASON_FUNDS_LOCKED_RESUMABLE, cause)

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
     *
     * FEE LEDGER: [balanceBefore] (the previous step's post-write snapshot) and
     * one best-effort post-registration read give the DPNS step's credit cost
     * ([IdentityFeeLedger]); the read is skipped for a provably pre-broadcast
     * NOT_REGISTERED (nothing was spent — [NameRegistration.balanceAfter] is
     * then [balanceBefore]). Never affects the returned status.
     */
    private suspend fun registerNameTracked(
        walletId: String,
        identityId: ByteArray,
        identityIdBase58: String,
        label: String,
        balanceBefore: Long?,
        preorderState: IdentityCreationState,
        registeringState: IdentityCreationState,
        registeredState: IdentityCreationState,
        // The identity already owns or contends for [label]: never submitted again, counted as registered.
        taken: Boolean = false,
        // A name-only retry: re-read [nameAlreadyTaken] right before submitting (fail closed).
        recheckOwnership: Boolean = false
    ): NameRegistration {
        // Re-check → submit → record under the identity's DPNS lock, so no other in-app
        // registration can land between the re-check and the submit. The create path re-reads
        // ownership only when another registration held the lock first (its identity is new).
        val locked = dpnsLock.withIdentityLock(identityIdBase58) { waited ->
            val alreadyOurs = when {
                taken -> true
                recheckOwnership || waited -> ownershipRecheck(identityIdBase58, label)
                    ?: return@withIdentityLock ownershipRecheckFailed(label, registeringState, balanceBefore)
                else -> false
            }
            if (alreadyOurs) {
                log.info("'{}' is already this identity's; not submitting it again", label)
                driveCreationState(registeredState, null)
                return@withIdentityLock NameRegistration(ShieldedUsernameNameStatus.REGISTERED, null, balanceBefore)
            }
            submitNameTracked(walletId, identityId, label, preorderState, registeringState, registeredState)
        }
        // Not submitted (skipped as ours, or refused): nothing was spent.
        if (!locked.submitted) return locked
        // The fee read stays outside the lock: it is log-only and can take its full timeout.
        val balanceAfter = if (locked.status == ShieldedUsernameNameStatus.NOT_REGISTERED) {
            balanceBefore
        } else {
            recordDpnsFee(identityIdBase58, label, locked.status.name, balanceBefore)
        }
        return NameRegistration(locked.status, locked.failureReason, balanceAfter)
    }

    /**
     * The submit + record part of [registerNameTracked], run under the identity's DPNS lock.
     * [NameRegistration.balanceAfter] is left null; the caller reads it after releasing the lock.
     */
    private suspend fun submitNameTracked(
        walletId: String,
        identityId: ByteArray,
        label: String,
        preorderState: IdentityCreationState,
        registeringState: IdentityCreationState,
        registeredState: IdentityCreationState
    ): NameRegistration {
        try {
            driveCreationState(preorderState, null)
            driveCreationState(registeringState, null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to record DPNS registering state for '{}'", label, t)
        }
        val (nameStatus, nameReason) = try {
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
        return NameRegistration(nameStatus, nameReason, balanceAfter = null, submitted = true)
    }

    /**
     * The ownership read repeated right before [label] is submitted, under the identity's
     * [DpnsRegistrationLock]: an earlier answer can be stale by then (an earlier Ambiguous attempt
     * that has landed since, another in-app registration, the same name requested from another
     * device). Null when it cannot be read.
     */
    private suspend fun ownershipRecheck(identityIdBase58: String, label: String): Boolean? = try {
        withContext(ioDispatcher) { nameAlreadyTaken(identityIdBase58, label) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("could not re-check '{}' before registering it; not submitting it", label, e)
        null
    }

    /**
     * Fail closed on an unreadable [ownershipRecheck]: [label] is not submitted and counts as not
     * registered, like a pre-broadcast rejection (for a primary, the caller stamps its
     * [NAME_ONLY_RETRY_ERROR]). Nothing was spent.
     */
    private suspend fun ownershipRecheckFailed(
        label: String,
        registeringState: IdentityCreationState,
        balanceBefore: Long?
    ): NameRegistration {
        val reason = "could not check '$label' before registering it"
        try {
            driveCreationState(registeringState, reason)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.warn("failed to stamp DPNS registration state for '{}'", label, e)
        }
        return NameRegistration(ShieldedUsernameNameStatus.NOT_REGISTERED, reason, balanceBefore)
    }

    /**
     * Result of [registerNameTracked]: the name [status] + [failureReason], and
     * the identity's post-step credit balance ([balanceAfter], null when
     * unread) — the next step's fee-ledger "before" snapshot.
     */
    private data class NameRegistration(
        val status: ShieldedUsernameNameStatus,
        val failureReason: String?,
        val balanceAfter: Long?,
        /** Whether `registerDpnsName` was called (false: skipped as ours, or refused). */
        val submitted: Boolean = false
    )

    /**
     * FEE LEDGER — IdentityCreate step. Credited = the asset lock's LOCKED
     * amount × 1000 (read from the SDK's retaining `asset_locks` row, which
     * also covers a resumed lock; falls back to [requestedDuffs] — this
     * attempt's funding amount — when the row is unreadable, and says so in
     * the line). Returns the post-create balance (null when unread). Strictly
     * best-effort: never throws (bar cancellation), never affects the flow.
     */
    private suspend fun recordIdentityCreateFee(
        walletId: String,
        identityIndex: Int,
        identityIdBase58: String,
        requestedDuffs: Long?
    ): Long? {
        val lockedDuffs = try {
            source.registrationAssetLockAmountDuffs(walletId, identityIndex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.debug("identity fee ledger: asset-lock amount read failed at index {}", identityIndex, t)
            null
        }
        val balanceAfter = IdentityFeeLedger.readBalanceOrNull(
            IdentityFeeStep.IDENTITY_CREATE,
            identityIdBase58,
            feeLedgerReadTimeoutMs
        ) {
            source.identityCreditBalance(identityIdBase58)
        }
        // No start at all on a resume whose lock amount can't be read: a guessed
        // one would record a nonsensical (negative) IdentityCreate fee.
        val credited = (lockedDuffs ?: requestedDuffs)?.let { duffs ->
            try {
                IdentityFeeLedger.creditedCredits(duffs)
            } catch (e: ArithmeticException) {
                null
            }
        }
        IdentityFeeLedger.record(
            step = IdentityFeeStep.IDENTITY_CREATE,
            identityIdBase58 = identityIdBase58,
            start = credited,
            after = balanceAfter,
            startSource = if (lockedDuffs != null) "assetLock" else "requested"
        )
        return balanceAfter
    }

    /**
     * FEE LEDGER — one DPNS registration: reads the post-step balance and logs
     * [balanceBefore] − it. `registerDpnsName` runs preorder + domain in ONE
     * SDK call with no fee info returned, so the figure is the COMBINED cost;
     * for a contested label it also includes the prefunded voting balance Drive
     * moves out of the identity. Returns the post-step balance (null when unread).
     */
    private suspend fun recordDpnsFee(
        identityIdBase58: String,
        label: String,
        status: String,
        balanceBefore: Long?
    ): Long? {
        val balanceAfter = IdentityFeeLedger.readBalanceOrNull(
            IdentityFeeStep.DPNS,
            identityIdBase58,
            feeLedgerReadTimeoutMs
        ) {
            source.identityCreditBalance(identityIdBase58)
        }
        val contested = try {
            Names.isUsernameContestable(label)
        } catch (e: Exception) {
            false
        }
        IdentityFeeLedger.record(
            step = IdentityFeeStep.DPNS,
            identityIdBase58 = identityIdBase58,
            start = balanceBefore,
            after = balanceAfter,
            label = label,
            status = status,
            note = if (contested) {
                "preorder+domain combined, includes contested prefunded voting balance"
            } else {
                "preorder+domain combined"
            }
        )
        return balanceAfter
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
     *
     * Under the identity's [DpnsRegistrationLock], [nameAlreadyTaken] is re-read right before the
     * submit: a label the identity already owns or contends for is not submitted again and is
     * reported as [SdkWriteResult.Broadcast]; an unreadable check is
     * [SdkWriteResult.NotBroadcast] (fail closed, retry-safe).
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
        // FEE LEDGER (log-only, best-effort): one snapshot before the
        // registration, one after (skipped when provably nothing was spent).
        val balanceBefore = IdentityFeeLedger.readBalanceOrNull(
            IdentityFeeStep.DPNS,
            identityIdBase58,
            feeLedgerReadTimeoutMs
        ) {
            source.identityCreditBalance(identityIdBase58)
        }
        // Re-check → submit under the identity's DPNS lock: the caller's own on-chain check ran
        // before it, so another in-app registration may have landed this name since. A label the
        // identity already owns or contends for is reported as broadcast and not submitted again.
        var submitted = false
        val result = dpnsLock.withIdentityLock(identityIdBase58) {
            when (ownershipRecheck(identityIdBase58, label)) {
                null -> SdkWriteResult.NotBroadcast("could not check '$label' before registering it")
                true -> {
                    log.info("'{}' is already this identity's; not submitting it again", label)
                    SdkWriteResult.Broadcast("$label.dash")
                }
                false -> try {
                    submitted = true
                    SdkWriteResult.Broadcast(source.registerDpnsName(walletId, identityId, label))
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    classifyBroadcastFailure(t)
                }
            }
        }
        // Not submitted: nothing was spent, no fee line.
        if (!submitted) return result
        val status = when (result) {
            is SdkWriteResult.Broadcast -> ShieldedUsernameNameStatus.REGISTERED
            is SdkWriteResult.NotBroadcast -> null
            is SdkWriteResult.Ambiguous -> ShieldedUsernameNameStatus.AMBIGUOUS
        }
        if (status != null) recordDpnsFee(identityIdBase58, label, status.name, balanceBefore)
        return result
    }

    /**
     * Name-only retry for an identity that is already registered on chain and only needs a
     * (new) DPNS name: a lost or locked vote, or a rejected domain document. It never funds and
     * never resets the identity. The requested name(s) are registered for the EXISTING identity
     * the way the create path registers them ([registerNamesAndHandOff]): the primary, then a
     * requested secondary once the primary is confirmed, then the same recovery handoff. The
     * requested name becomes the primary/display name, while a registered secondary that is not
     * re-requested stays the secondary, as master's dashj `finishRegistration` does. A label the
     * identity already owns or contends for is not submitted again ([nameAlreadyTaken]: read before
     * accepting, and again right before each name is submitted).
     *
     * The legacy CreateIdentityService reuse path must not be used for this after the cutover: it
     * replaces the identity record with a fresh one at NONE and looks for a dashj asset-lock
     * transaction the SDK creation path never put in dashj's AuthenticationGroupExtension, so it
     * starts fresh legacy funding.
     *
     * Runs on the application scope, like [submit], and is registered with the wipe fence: the
     * caller's screen finishes as soon as this is accepted (review 5556781925). Returns false,
     * touching nothing, when there is no executor scope, a wallet wipe is in progress, the wallet
     * is not bound, or the identity's names cannot be read (fail closed). Once accepted, a failure
     * (a context write, a name that does not register or whose outcome is unknown) stamps a
     * [NAME_ONLY_RETRY_ERROR], which the home tile sends back here.
     */
    suspend fun retryNameForExistingIdentity(identityIdBase58: String, username: String, secondaryUsername: String?): Boolean {
        val scope = executorScope
        if (scope == null) {
            log.warn("name-only retry refused: no executor scope")
            return false
        }
        if (secretWriteFence.isRaised) {
            log.warn("name-only retry refused: wallet wipe in progress")
            return false
        }
        // Single-flight with [submit], for the whole run: a double tap must not register the same
        // label twice (a contested name's prefund twice).
        synchronized(this) {
            if (nameRetryInFlight || _submitState.value == ShieldedUsernameSubmitState.Proving) {
                log.warn("name-only retry refused: another name operation is running")
                return false
            }
            nameRetryInFlight = true
        }
        // Freed exactly once per run, whichever of the refusal below and the job's completion
        // handler gets there first: a second release could free a slot another caller holds.
        val released = AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) releaseNameRetry() }
        var launched = false
        try {
            return launchNameRetry(scope, identityIdBase58, username, secondaryUsername, release).also { launched = it }
        } finally {
            if (!launched) release()
        }
    }

    private fun releaseNameRetry() {
        synchronized(this) { nameRetryInFlight = false }
    }

    /** The rest of [retryNameForExistingIdentity], once it holds the single-flight slot. */
    private suspend fun launchNameRetry(
        scope: CoroutineScope,
        identityIdBase58: String,
        username: String,
        secondaryUsername: String?,
        release: () -> Unit
    ): Boolean {
        // Resolved before accepting, failing closed: a refusal touches nothing and shows the
        // screen's error, so no stamped error can send a later "Try again" anywhere else.
        val resolved = try {
            // All of it off the caller's dispatcher (Main, from a viewModelScope): the wallet-id
            // read can cold-start the SDK and the ownership checks are Platform queries.
            withContext(ioDispatcher) {
                val walletId = source.boundWalletIdOrNull()
                    ?: throw IllegalStateException("app wallet not bound to the SDK")
                val identityId = Identifier.from(identityIdBase58).toBuffer()
                // A label this identity already owns or contends for (an earlier Ambiguous attempt
                // that landed, for instance) is never submitted again.
                val alreadyTaken = listOfNotNull(username, secondaryUsername)
                    .filter { nameAlreadyTaken(identityIdBase58, it) }
                    .toSet()
                Triple(walletId, identityId, alreadyTaken)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("name-only retry refused: could not check the identity's names", e)
            return false
        }
        // LAZY + tracked before start: the job can never run unfenced.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                retryNameNow(identityIdBase58, username, secondaryUsername, resolved)
            } catch (e: CancellationException) {
                // the wipe fence cancelling the run: nothing to record for a wallet being wiped
                throw e
            } catch (e: Exception) {
                // Never out of the launch: the application scope has no handler, so an escaped
                // exception crashes the process after the screen was told the retry started.
                // Record a retryable error so the retry card shows.
                log.error("name-only retry for identity {} failed", identityIdBase58, e)
                recordRetryFailure(e)
            }
        }
        if (!trackOrRefuse(job)) {
            // the caller's finally releases the single-flight slot
            log.warn("name-only retry refused: wallet wipe in progress")
            return false
        }
        // [release] frees the slot once, whether this handler or the caller's finally (on a
        // refusal) runs first. A wipe cancelling the job before this line still releases it: the
        // handler runs at once on a completed job.
        job.invokeOnCompletion { release() }
        if (!startOrRefuse(job)) {
            // a wipe cancelled it between the track and the start; the caller's finally releases
            log.warn("name-only retry refused: wallet wipe in progress")
            return false
        }
        return true
    }

    /** Best effort: if the state write fails as well, the failure is only logged. */
    private suspend fun recordRetryFailure(cause: Exception) {
        try {
            driveCreationState(
                IdentityCreationState.USERNAME_REGISTERING,
                "$NAME_ONLY_RETRY_ERROR: ${cause.message ?: cause.javaClass.simpleName}"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("could not record the failed name-only retry", e)
        }
    }

    private suspend fun retryNameNow(
        identityIdBase58: String,
        username: String,
        secondaryUsername: String?,
        resolved: Triple<String, ByteArray, Set<String>>
    ) {
        log.info("name-only retry for existing identity {}: requesting a new DPNS name", identityIdBase58)
        val (walletId, identityId, alreadyTaken) = resolved
        val contested = Names.isUsernameContestable(username) ||
            (secondaryUsername != null && Names.isUsernameContestable(secondaryUsername))
        // The requested names become the record's (a secondary only when one is requested, so a
        // registered secondary stays), restoring=false while they land, as in the create path.
        persistNameContext(identityIdBase58, username, secondaryUsername, false)
        clearPreviousNameRequest()
        val balanceBefore = IdentityFeeLedger.readBalanceOrNull(
            IdentityFeeStep.DPNS,
            identityIdBase58,
            feeLedgerReadTimeoutMs
        ) {
            source.identityCreditBalance(identityIdBase58)
        }
        registerNamesAndHandOff(
            walletId,
            identityId,
            identityIdBase58,
            username,
            secondaryUsername,
            contested,
            balanceBefore,
            alreadyTaken,
            nameOnlyRetry = true
        )
    }

    private fun notBroadcast(reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("transparent username creation not attempted ({})", reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkTransparentUsernameCreation::class.java)

        /**
         * Prefix of the error a name-only retry stamps when its name did not register (or its
         * outcome is unknown). The home tile sends such an error back into the name-only retry
         * ([isNameOnlyRetryError]), never into the legacy CreateIdentityService retry or the
         * restore worker, which would drop the replacement name.
         */
        const val NAME_ONLY_RETRY_ERROR = "username registration did not complete (retryable, name only)"

        /** True when [creationStateErrorMessage] was stamped by a name-only retry. */
        fun isNameOnlyRetryError(creationStateErrorMessage: String?): Boolean =
            creationStateErrorMessage?.startsWith(NAME_ONLY_RETRY_ERROR) == true

        /** BIP44 account the identity asset lock is funded from (same as the SDK's plain send). */
        private const val ACCOUNT_INDEX = 0

        /**
         * Back-off before each automatic resume after a FALSE-LOCKED Keystore
         * refusal (see [recoverFromKeystoreRefusal]) — bounded to two attempts.
         */
        internal val AUTO_RESUME_DELAYS_MS = longArrayOf(500L, 1_500L)

        /**
         * [SdkWriteResult.NotBroadcast] reason when the Platform transition was
         * refused pre-broadcast but the identity's L1 asset lock IS built and
         * tracked: the funds are committed and the next submit RESUMES that
         * lock (no second payment).
         */
        internal const val REASON_FUNDS_LOCKED_RESUMABLE =
            "identity create not signed (Keystore refused the signing key); the funding asset lock is " +
                "tracked and the next attempt resumes it at no extra cost"

        /**
         * [SdkWriteResult.NotBroadcast] reason when the Platform transition was
         * refused by the Keystore signer and the tracked-lock re-check FAILED,
         * so whether the L1 asset lock was already broadcast is UNKNOWN: the
         * funds may be committed. Handled exactly like
         * [REASON_FUNDS_LOCKED_RESUMABLE] (re-submittable): the next submit's
         * resume gate re-checks and resumes any tracked lock, and refuses a
         * fresh build while that lookup fails — no second payment.
         */
        internal const val REASON_FUNDING_UNRESOLVED_RESUMABLE =
            "identity create not signed (Keystore refused the signing key) and the funding asset-lock check " +
                "failed; the funds may be committed — the next attempt verifies and resumes any tracked lock " +
                "at no extra cost"

        /**
         * True for the [SdkWriteResult.NotBroadcast] reasons whose funding MAY
         * be committed on L1 yet stay re-submittable because the next submit
         * resumes the tracked lock ([REASON_FUNDS_LOCKED_RESUMABLE],
         * [REASON_FUNDING_UNRESOLVED_RESUMABLE]).
         */
        fun isFundsPossiblyCommittedReason(reason: String?): Boolean =
            reason == REASON_FUNDS_LOCKED_RESUMABLE || reason == REASON_FUNDING_UNRESOLVED_RESUMABLE
    }
}
