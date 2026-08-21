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

import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.database.entity.TopUp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Sha256Hash
import org.dashfoundation.dashsdk.wallet.TrackedAssetLock
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Source seam ───────────────────────────────────────────────────────

/**
 * The SDK-managed identity a top-up targets: its 32-byte [identityId] (the
 * bytes the SDK's own managed-identity list carries) and its DIP-9
 * [registrationIndex] on the identity chain. The registration index is the
 * key the resume gate matches a pending top-up asset lock on, so it MUST come
 * from the SDK's authoritative managed list — never inferred client-side.
 */
internal data class ManagedIdentityRef(
    val identityId: ByteArray,
    val registrationIndex: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ManagedIdentityRef) return false
        return identityId.contentEquals(other.identityId) &&
            registrationIndex == other.registrationIndex
    }

    override fun hashCode(): Int = 31 * identityId.contentHashCode() + registrationIndex
}

/**
 * Seam over the Kotlin SDK's identity-CREDITS (top-up) surface
 * ([org.dashfoundation.dashsdk.credits.IdentityCredits]), so the
 * resume-gate / no-double-broadcast orchestration in [SdkTransparentTopUp] is
 * host-JVM unit-testable — the real calls need `libdash_sdk`.
 *
 * The sibling of [TransparentUsernameSource]: same wallet/handle plumbing,
 * but the funding call TOPS UP an EXISTING identity from the wallet's
 * transparent Core UTXOs instead of registering a new one. The top-up FFI
 * FUSES the L1 asset-lock build-from-Core with the Platform top-up
 * registration and returns the new credit balance — there is no intermediate
 * dashj transaction or txid.
 */
internal interface TransparentTopUpSource {
    /** Same contract as [SdkDashPayWriteSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * Resolve [identityIdBase58] against the bound SDK wallet's AUTHORITATIVE
     * managed-identity list, returning its 32-byte id + DIP-9 registration
     * index, or null when the SDK does not manage this identity (so its
     * registration index cannot be reliably obtained — the caller MUST then
     * refuse to build a fresh asset lock, since the resume gate could not
     * match a pending top-up without the index = double-pay risk).
     */
    suspend fun resolveManagedIdentity(walletIdHex: String, identityIdBase58: String): ManagedIdentityRef?

    /**
     * RESUME-GATE query: the UNRESOLVED top-up asset lock the SDK durably
     * tracks for [registrationIndex] (a killed prior attempt that already
     * built — and possibly broadcast — an asset lock), or null when none is
     * outstanding. Backed by
     * [org.dashfoundation.dashsdk.wallet.PlatformWalletManager.trackedIdentityRecoveryAssetLocks],
     * the SDK's RECOVERY query — it returns only locks whose funding has not
     * completed, so any returned lock of top-up [TrackedAssetLock.FundingType]
     * ({IDENTITY_TOP_UP, IDENTITY_TOP_UP_NOT_BOUND}) at
     * [TrackedAssetLock.getRegistrationIndex] == [registrationIndex] must be
     * RESUMED, never rebuilt (rebuilding selects fresh UTXOs = DOUBLE-PAY).
     */
    suspend fun unresolvedTopUpAssetLock(walletIdHex: String, registrationIndex: Int): TrackedAssetLock?

    /**
     * RELIABLE post-success txid capture: the DISPLAY-order txid hex of the
     * top-up asset lock ({IDENTITY_TOP_UP, IDENTITY_TOP_UP_NOT_BOUND}) the SDK
     * persisted for [registrationIndex], or null when none is found. Reads the
     * SDK's `asset_locks` Room table (which RETAINS consumed rows) rather than
     * the ELIGIBLE-ONLY native recovery surface ([unresolvedTopUpAssetLock])
     * that DROPS a lock the fresh top-up just consumed — the source of the
     * "Internal" mislabel. The table's `outPointHex` is already
     * `"<display-txid>:<vout>"`, so the returned value needs no wire→display
     * conversion.
     */
    suspend fun topUpAssetLockTxidDisplayHex(walletIdHex: String, registrationIndex: Int): String?

    /**
     * PLAIN build: top up [identityId] with [amountDuffs] Core duffs from
     * BIP44 [accountIndex], funded by a FRESH asset lock built from the
     * wallet's transparent Core UTXOs. Never consults existing tracked locks
     * — the caller MUST run the resume gate first. Returns the identity's new
     * credit balance, or null when the FFI reported no balance (an
     * unconfirmed outcome the caller treats as ambiguous).
     */
    suspend fun topUpFromCore(
        walletIdHex: String,
        identityId: ByteArray,
        amountDuffs: Long,
        accountIndex: Int
    ): Long?

    /**
     * RESUME build: top up [identityId] reusing the SAME [lock] a prior
     * attempt already built (no fresh UTXO selection — the only re-entry that
     * cannot double-pay). Returns the identity's new credit balance, or null
     * (treated as ambiguous, exactly like [topUpFromCore]).
     */
    suspend fun resumeTopUpFromAssetLock(
        walletIdHex: String,
        identityId: ByteArray,
        lock: TrackedAssetLock
    ): Long?
}

/** Production [TransparentTopUpSource]: boots the SDK on demand. */
internal class DashSdkTransparentTopUpSource(
    private val service: DashSdkService
) : TransparentTopUpSource {

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

    override suspend fun resolveManagedIdentity(
        walletIdHex: String,
        identityIdBase58: String
    ): ManagedIdentityRef? {
        // base58 → the raw 32-byte identity id used to match the SDK's own
        // managed-identity rows. A malformed id throws and is contained by the
        // caller's fail-closed lookup handling.
        val target = Identifier.from(identityIdBase58).toBuffer()
        val state = wallet(walletIdHex).inMemoryIdentityStates()
            .firstOrNull { it.identityId.contentEquals(target) }
            ?: return null
        // getIndex() is the DIP-9 identity-chain registration index; it is the
        // resume-gate key. Use the SDK's own identityId bytes for the calls.
        return ManagedIdentityRef(state.identityId, state.index.toInt())
    }

    override suspend fun unresolvedTopUpAssetLock(
        walletIdHex: String,
        registrationIndex: Int
    ): TrackedAssetLock? =
        manager().trackedIdentityRecoveryAssetLocks(walletId(walletIdHex))
            .firstOrNull {
                (it.fundingType == TrackedAssetLock.FundingType.IDENTITY_TOP_UP ||
                    it.fundingType == TrackedAssetLock.FundingType.IDENTITY_TOP_UP_NOT_BOUND) &&
                    it.registrationIndex == registrationIndex
            }

    override suspend fun topUpAssetLockTxidDisplayHex(
        walletIdHex: String,
        registrationIndex: Int
    ): String? =
        service.databaseOrNull()?.assetLockDao()
            ?.observeByWalletAndIdentityIndex(walletId(walletIdHex), registrationIndex)
            ?.first()
            ?.firstOrNull {
                it.fundingTypeRaw == TrackedAssetLock.FundingType.IDENTITY_TOP_UP.raw ||
                    it.fundingTypeRaw == TrackedAssetLock.FundingType.IDENTITY_TOP_UP_NOT_BOUND.raw
            }
            ?.outPointHex?.substringBefore(':')

    override suspend fun topUpFromCore(
        walletIdHex: String,
        identityId: ByteArray,
        amountDuffs: Long,
        accountIndex: Int
    ): Long? {
        val manager = manager()
        return manager.identityCredits.topUpFromCore(
            wallet(walletIdHex).handle,
            identityId,
            amountDuffs,
            accountIndex,
            manager.mnemonicResolverHandle
        )
    }

    override suspend fun resumeTopUpFromAssetLock(
        walletIdHex: String,
        identityId: ByteArray,
        lock: TrackedAssetLock
    ): Long? {
        val manager = manager()
        return manager.identityCredits.resumeTopUpWithExistingAssetLock(
            wallet(walletIdHex).handle,
            identityId,
            lock,
            manager.mnemonicResolverHandle
        )
    }
}

// ── Service ───────────────────────────────────────────────────────────

/**
 * TRANSPARENT-funded identity TOP-UP ("Buy Credits") — the post-cutover
 * replacement for the dashj asset-lock funding path in
 * [de.schildbach.wallet.ui.send.BuyCreditsFragment] /
 * the deleted legacy TopupIdentityWorker. Once the
 * cutover is committed the dashj L1 engine is HELD (0 UTXOs), so building the
 * top-up asset lock with dashj fails `InsufficientMoneyException` — the funds
 * live in the SDK. This routes top-up funding through the SDK's
 * `topUpFromCore` (or, on re-entry, `resumeTopUpWithExistingAssetLock`) which
 * FUSES the asset-lock build-from-Core with the Platform top-up registration
 * and returns the new credit balance — so there is NO dashj tx/txid to feed
 * the legacy txId-keyed [TopupIdentityWorker] chain, and the app observes this
 * executor's direct [SdkWriteResult] outcome instead.
 *
 * Structural sibling of [SdkTransparentUsernameCreation] — same three-valued
 * [SdkWriteResult] contract, same MANDATORY resume gate, same fail-closed
 * discipline. Differences:
 * - it tops up an EXISTING identity rather than creating one, so the target's
 *   [ManagedIdentityRef.registrationIndex] is resolved from the SDK's
 *   AUTHORITATIVE managed-identity list ([TransparentTopUpSource.resolveManagedIdentity]);
 *   an identity the SDK does not manage (no reliable index) fails closed;
 * - the amount is the USER-ENTERED top-up value in Core duffs, not a fixed
 *   username-registration fee;
 * - there is no DPNS-name step and no username outcome — the payload is simply
 *   the new credit balance.
 *
 * Funds safety: there is NO dashj↔SDK fallback on a committed cutover. A
 * MANDATORY RESUME GATE runs before every plain funding call — the SDK commits
 * the asset lock at status Built to SQLite SYNCHRONOUSLY before broadcasting,
 * so a killed + retried top-up reliably finds the same tracked lock and RESUMES
 * it (re-consuming the SAME UTXOs) instead of building a second (different UTXO
 * selection = DOUBLE-PAY). A funding failure that is provably pre-broadcast is
 * [SdkWriteResult.NotBroadcast] (retry-safe — the resume gate re-consumes any
 * lock a retry finds); anything else (including a null FFI return that may have
 * broadcast) is [SdkWriteResult.Ambiguous] — STICKY: this process refuses any
 * further top-up until it restarts (only the resume gate may re-enter after a
 * restart), so the UI can never present a retry that double-pays.
 */
@Singleton
class SdkTransparentTopUp internal constructor(
    private val source: TransparentTopUpSource,
    /**
     * True once the cutover is COMMITTED (persisted CUT_OVER / SETTLED — the
     * dashj engine is held). This path is only ever ROUTED to post-cutover;
     * the gate is a belt-and-suspenders fail-closed so a stray pre-cutover
     * call submits nothing.
     */
    private val cutoverCommitted: suspend () -> Boolean,
    /**
     * Record a [TopUp] row keyed by the top-up asset-lock txid (DISPLAY-order
     * hex) for the given identity, so the app-side records can label this L1
     * asset lock as a "Topup" instead of the SDK's neutral "Internal"
     * classification (AssetLockKindResolver reads TopUpsDao.getByTxId). The SDK
     * top-up FFI fuses the asset-lock build with the Platform registration and
     * returns no dashj tx/txid, so nothing else records this row. Best-effort —
     * no-op default keeps the host-JVM tests (which have no Room) inert.
     */
    private val recordTopUp: suspend (txDisplayHex: String, toUserId: String) -> Unit = { _, _ -> },
    /**
     * Seed the asset-lock kind ([AssetLockKind.TOPUP]) in-memory the instant
     * the top-up funding txid is known, so the FIRST time the engine feed
     * classifies this L1 asset lock it already reads as "Topup" instead of
     * momentarily showing "Internal" until the [recordTopUp] Room insert lands
     * (the visible-flip fix). Best-effort — no-op default keeps the host-JVM
     * tests inert.
     */
    private val seedAssetLockKind: (displayHex: String, kind: AssetLockKind) -> Unit = { _, _ -> }
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        sdkL1SendService: SdkL1SendService,
        topUpsDao: TopUpsDao,
        assetLockKindResolver: AssetLockKindResolver
    ) : this(
        source = DashSdkTransparentTopUpSource(sdkService),
        cutoverCommitted = { sdkL1SendService.cutoverCommitted() },
        recordTopUp = { txDisplayHex, toUserId ->
            topUpsDao.insert(TopUp(Sha256Hash.wrap(txDisplayHex), toUserId))
        },
        seedAssetLockKind = { displayHex, kind ->
            assetLockKindResolver.seed(displayHex, kind)
        }
    )

    /** Test seam: the funding call blocks on network I/O — keep it off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Process-level guard shared across BuyCredits screen instances (this is a
     * [Singleton]): single-flight + STICKY-ambiguous. Guards every entry to
     * [topUp] under [this].
     */
    private enum class Guard { IDLE, IN_FLIGHT, AMBIGUOUS_STICKY }

    private var guard: Guard = Guard.IDLE
    private var stickyAmbiguousCause: Throwable? = null

    /**
     * Whether the cutover is committed — the routing signal the BuyCredits go
     * handler uses to pick this path over the dashj asset-lock path. Contained:
     * a read failure reads as NOT committed (dashj rules).
     */
    suspend fun isCutoverCommitted(): Boolean = try {
        cutoverCommitted()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        log.warn("failed to read the cutover state; treating as not committed", e)
        false
    }

    /**
     * Top up [identityIdBase58] by [amountDuffs] Core duffs (the user-entered
     * amount), resume-gated. The awaited entry point the BuyCredits go handler
     * calls inline; it observes the returned [SdkWriteResult] directly. Enforces
     * single-flight + sticky-ambiguous under [this]:
     * - a concurrent in-flight top-up → [SdkWriteResult.NotBroadcast] (nothing
     *   attempted);
     * - a prior AMBIGUOUS outcome this process → sticky [SdkWriteResult.Ambiguous]
     *   (never retried);
     * - a Broadcast/NotBroadcast outcome resets to IDLE so a later, deliberate
     *   top-up (or a retry after a provably-pre-broadcast refusal) is allowed.
     *
     * [newUserIntent] MUST be true only for the FIRST execution of a freshly
     * enqueued purchase (a deliberate user tap that has not reached the SDK
     * before). It is what legitimizes the fresh-build-on-consumed carve-out in
     * [topUpTransparent]: on a new intent, a tracked lock Platform rejects as
     * already consumed PREDATES this request (stale bookkeeping from an earlier
     * purchase) and may be ignored. On a WorkManager RERUN of the same purchase
     * (the process died after the SDK hand-off) it must be FALSE: the consumed
     * lock is then this purchase's OWN completed asset lock, and fresh-building
     * would broadcast a SECOND full-amount lock — one tap, two charges.
     * Deliberately no default: every caller must decide.
     */
    suspend fun topUp(
        identityIdBase58: String,
        amountDuffs: Long,
        newUserIntent: Boolean
    ): SdkWriteResult<Long> {
        synchronized(this) {
            when (guard) {
                Guard.IN_FLIGHT -> {
                    log.warn("transparent top-up refused: an operation is already in flight")
                    return SdkWriteResult.NotBroadcast("a top-up is already in progress")
                }
                Guard.AMBIGUOUS_STICKY -> {
                    log.error("transparent top-up refused: a prior top-up outcome is unconfirmed (sticky)")
                    return SdkWriteResult.Ambiguous(
                        stickyAmbiguousCause
                            ?: IllegalStateException("previous top-up outcome unconfirmed")
                    )
                }
                Guard.IDLE -> guard = Guard.IN_FLIGHT
            }
        }
        val result = try {
            withContext(ioDispatcher) { topUpTransparent(identityIdBase58, amountDuffs, newUserIntent) }
        } catch (t: Throwable) {
            // A thrown (non-cancellation) failure escaping the pipeline is, by
            // the money-path contract, treated as unconfirmed: it may have
            // broadcast. Make it sticky. Cancellation resets so a retry is
            // possible (nothing about a cancelled coroutine confirms a spend
            // beyond what the resume gate already covers).
            synchronized(this) {
                guard = if (t is CancellationException) Guard.IDLE else Guard.AMBIGUOUS_STICKY
                if (t !is CancellationException) stickyAmbiguousCause = t
            }
            throw t
        }
        synchronized(this) {
            when (result) {
                is SdkWriteResult.Ambiguous -> {
                    guard = Guard.AMBIGUOUS_STICKY
                    stickyAmbiguousCause = result.cause
                }
                // Both Broadcast (a real, completed top-up — repeatable) and
                // NotBroadcast (provably nothing spent) return to IDLE.
                else -> guard = Guard.IDLE
            }
        }
        return result
    }

    /**
     * The full pipeline. One funding attempt (resume-gated); the
     * [SdkWriteResult] three-valued contract holds
     * ([SdkWriteResult.Ambiguous] is never retried by anyone).
     */
    suspend fun topUpTransparent(
        identityIdBase58: String,
        amountDuffs: Long,
        /** See [topUp] — gates the fresh-build-on-consumed carve-out below. */
        newUserIntent: Boolean,
        /**
         * Internal: the outpoint of a tracked lock the resume gate must
         * IGNORE — set on the single self-retry taken after that lock was
         * rejected as already-consumed (see the catch below). Non-null also
         * means "this is the retry", so it can never loop.
         */
        skipLockOutpoint: Pair<ByteArray, Int>? = null
    ): SdkWriteResult<Long> {
        // Fail closed unless the cutover is committed — pre-cutover this path
        // must submit nothing (the dashj path owns funding then).
        val committed = try {
            cutoverCommitted()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("cutover state read failed", t)
        }
        if (!committed) return SdkWriteResult.NotBroadcast("cutover not committed")

        if (amountDuffs <= 0L) {
            return notBroadcast("non-positive top-up amount", null)
        }
        val idBase58 = identityIdBase58.trim()
        if (idBase58.isEmpty()) {
            return notBroadcast("no identity to top up", null)
        }

        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast("app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("SDK bootstrap/bind lookup failed", t)
        }

        // Resolve the target identity's id + DIP-9 registration index from the
        // SDK's AUTHORITATIVE managed list. Without a reliable registration
        // index the resume gate cannot match a pending top-up, so a lookup
        // FAILURE or a not-managed identity fails closed (never a fresh build).
        val ref = try {
            source.resolveManagedIdentity(walletId, idBase58)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("identity resolution failed", t)
        } ?: return notBroadcast("identity not managed by the SDK (no registration index)", null)

        // MANDATORY RESUME GATE — runs BEFORE the plain funding call. If a
        // killed prior attempt already built (and maybe broadcast) a top-up
        // asset lock for THIS registration index, resume it (re-consumes the
        // SAME UTXOs); otherwise build fresh. Calling the plain path while an
        // unresolved lock exists would select DIFFERENT UTXOs = DOUBLE-PAY.
        val existingLock = try {
            source.unresolvedTopUpAssetLock(walletId, ref.registrationIndex)
                // Drop a lock Platform already rejected as consumed on this
                // call's first pass: it is stale bookkeeping, not a resumable
                // candidate, and re-picking it would fail forever (the SDK
                // never marks it consumed locally — platform ask on MO-998).
                ?.takeUnless { lock ->
                    skipLockOutpoint?.let { (txid, vout) ->
                        lock.outpointTxid.contentEquals(txid) && lock.outpointVout == vout
                    } == true
                }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // A failed recovery lookup cannot prove no lock exists — refuse the
            // FRESH build rather than risk a double-pay. Retry-safe: nothing
            // was spent.
            return notBroadcast("resume-gate recovery lookup failed", t)
        }

        // THE funding spend — one attempt (resume or fresh build).
        val newBalance: Long? = try {
            if (existingLock != null) {
                log.info(
                    "resuming transparent identity top-up at index {} from tracked asset lock (status={})",
                    ref.registrationIndex,
                    existingLock.status
                )
                source.resumeTopUpFromAssetLock(walletId, ref.identityId, existingLock)
            } else {
                source.topUpFromCore(
                    walletIdHex = walletId,
                    identityId = ref.identityId,
                    amountDuffs = amountDuffs,
                    accountIndex = ACCOUNT_INDEX
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // A RESUME that Platform rejects as already-consumed is NOT
            // ambiguous: those credits provably landed on an earlier attempt.
            // What that MEANS depends on whose attempt it was:
            //
            // - NOT a new user intent (a WorkManager RERUN of this same
            //   purchase after a mid-flight process death): the consumed lock
            //   is this purchase's OWN asset lock — Platform consumed it
            //   before the death (the state observed live 2026-08-04), so the
            //   purchase COMPLETED. Terminal SUCCESS, never a fresh build:
            //   rebuilding here broadcast a SECOND full-amount asset lock for
            //   the same tap (one tap, two charges).
            //
            // - A NEW user intent (first execution of a fresh purchase): the
            //   consumed lock PREDATES this request — stale bookkeeping from
            //   an earlier purchase (the SDK never marks it consumed locally,
            //   platform ask on MO-998), so the resume gate would keep picking
            //   this dead lock and every future purchase would fail. Treat it
            //   as "not a resumable candidate" and retry the pipeline ONCE,
            //   which then takes the fresh-build branch. Safe: the rejection
            //   proves the lock's outputs are spent, and the flag stops it
            //   from ever looping.
            if (existingLock != null && isAlreadyConsumed(t) && skipLockOutpoint == null) {
                if (!newUserIntent) {
                    log.warn(
                        "rerun resume hit this purchase's own already-consumed lock at index {} — " +
                            "its credits landed on the earlier attempt; terminal success, no fresh build",
                        ref.registrationIndex
                    )
                    // Same best-effort Topup labelling as the success tail —
                    // the attempt that actually credited died before recording.
                    try {
                        val lockTxidDisplayHex = displayHexOf(existingLock.outpointTxid)
                        seedAssetLockKind(lockTxidDisplayHex, AssetLockKind.TOPUP)
                        recordTopUp(lockTxidDisplayHex, idBase58)
                    } catch (t2: Throwable) {
                        if (t2 is CancellationException) throw t2
                        log.warn("failed to capture/record the already-credited top-up asset-lock txid", t2)
                    }
                    // The rejection proved consumption, not a balance — report
                    // the sentinel; the credits are on the identity regardless.
                    return SdkWriteResult.Broadcast(BALANCE_ALREADY_CREDITED)
                }
                log.warn(
                    "resume hit an already-consumed lock at index {} (its credits landed earlier); " +
                        "ignoring that stale tracked lock and building fresh",
                    ref.registrationIndex
                )
                return topUpTransparent(
                    identityIdBase58,
                    amountDuffs,
                    newUserIntent,
                    skipLockOutpoint = existingLock.outpointTxid to existingLock.outpointVout
                )
            }
            return when (val classified = classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("transparent identity top-up rejected pre-broadcast", t)
                    classified
                }
                else -> {
                    log.error(
                        "transparent identity top-up outcome unconfirmed — the asset lock MAY be on " +
                            "chain; it stays durably tracked and the resume gate re-consumes it. Do NOT retry",
                        t
                    )
                    SdkWriteResult.Ambiguous(t)
                }
            }
        }

        if (newBalance == null) {
            // The FFI returned without a balance and without throwing. It FUSES
            // build + broadcast + registration, so a null return after entering
            // the native call is genuinely unconfirmed — it may have broadcast
            // an asset lock (which is durably tracked and the resume gate covers
            // on the next deliberate attempt). Fail SAFE: ambiguous, never
            // auto-retry.
            log.error(
                "transparent identity top-up at index {} returned no balance — outcome unconfirmed; " +
                    "the asset lock MAY be on chain and stays tracked. Do NOT retry",
                ref.registrationIndex
            )
            return SdkWriteResult.Ambiguous(
                IllegalStateException("top-up returned no credit balance")
            )
        }

        log.info(
            "transparent-funded identity top-up at index {} — {} duffs, new credit balance {}",
            ref.registrationIndex,
            amountDuffs,
            newBalance
        )

        // Capture the top-up asset-lock txid so the app-side records can label
        // this L1 asset lock as a "Topup" instead of the SDK's neutral "Internal"
        // classification (AssetLockKindResolver reads TopUpsDao.getByTxId). The
        // resume branch already holds the lock; the fresh branch re-queries the
        // recovery/resume-gate query (committed to the SDK store at status Built
        // before broadcast). Best-effort and post-success: a top-up that cannot
        // cleanly recover the txid still succeeds — the row stays "Internal".
        try {
            // The resume branch already holds the lock (wire-order txid → display).
            // The fresh branch's top-up CONSUMED the lock, so the eligible-only
            // recovery query now DROPS it — read the retaining `asset_locks` Room
            // table instead (already display form, no displayHexOf conversion).
            val lockTxidDisplayHex = existingLock?.let { displayHexOf(it.outpointTxid) }
                ?: source.topUpAssetLockTxidDisplayHex(walletId, ref.registrationIndex)
            if (lockTxidDisplayHex != null) {
                // Seed the kind in-memory FIRST (synchronous, same turn) so the
                // first feed classification already reads "Topup"; the Room
                // insert below is the durable backstop across restarts. Same
                // display-hex key the resolver hashes into TopUpsDao.getByTxId.
                seedAssetLockKind(lockTxidDisplayHex, AssetLockKind.TOPUP)
                recordTopUp(lockTxidDisplayHex, idBase58)
            } else {
                log.warn(
                    "top-up at index {} succeeded but its asset-lock txid was not recoverable; " +
                        "the row stays labelled Internal",
                    ref.registrationIndex
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to capture/record top-up asset-lock txid", t)
        }

        return SdkWriteResult.Broadcast(newBalance)
    }

    private fun notBroadcast(reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("transparent top-up not attempted ({})", reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkTransparentTopUp::class.java)

        /** BIP44 account the top-up asset lock is funded from (same as the SDK's plain send). */
        private const val ACCOUNT_INDEX = 0

        /**
         * [SdkWriteResult.Broadcast] value when a worker RERUN found this
         * purchase's own already-consumed asset lock: the credits provably
         * landed on the earlier attempt, but the rejected resume reported no
         * balance, so the new balance is unknown. The purchase itself is a
         * SUCCESS.
         */
        const val BALANCE_ALREADY_CREDITED = -1L
    }
}
