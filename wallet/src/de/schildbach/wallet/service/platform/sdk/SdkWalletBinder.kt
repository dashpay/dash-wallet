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
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.util.NativeLogBridge
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The birth time (Unix SECONDS) to hand [DashSdkService.bindAppWallet],
 * which maps it to the SDK `birthHeight` via [BirthHeightResolver].
 *
 * ## Why the dashj wallet alone is not the answer
 *
 * [org.bitcoinj.wallet.Wallet.getEarliestKeyCreationTime] is NOT a real
 * birth time for a seed restore: `DashWalletFactory.restoreWalletFromSeed`
 * deliberately stamps every restored seed with
 * [Constants.EARLIEST_HD_SEED_CREATION_TIME] ("always the oldest possible
 * time", 2015-03-29) so dashj can never miss history. The wallet birth
 * date the user picks on the restore screen is persisted SEPARATELY, in
 * [BlockchainServiceConfig.getWalletCreationDate] — which is exactly what
 * dashj's own fresh-store checkpointing reads
 * (`BlockchainServiceImpl`: `serviceConfig.getWalletCreationDate() ?:
 * wallet.earliestKeyCreationTime`). Reading only the wallet therefore
 * handed the resolver the 2015 sentinel on EVERY restore, i.e. a full
 * mainnet chain scan no matter which date was chosen.
 *
 * ## Rule: the earliest REAL signal, sentinel as the floor
 *
 * A value only carries information when it is strictly later than the
 * sentinel — [BlockchainServiceConfig.getWalletCreationDate] already
 * nulls the sentinel itself, and the wallet's time is filtered the same
 * way here (the rule [de.schildbach.wallet.ui.more.ToolsViewModel] uses).
 * Of the informative values we take the MINIMUM, not a precedence order:
 * a wallet restored from a protobuf backup carries a genuine key
 * creation time, and a later user-entered date must never be allowed to
 * skip past it. When neither is informative — the "user does not know
 * the date" path — we fall back to the raw wallet time, i.e. the 2015
 * sentinel, which resolves to the earliest possible HD-wallet height.
 * Scanning too early only costs time; scanning too late hides funds.
 */
internal fun sdkWalletBirthTimeSecs(
    configuredCreationDateSecs: Long?,
    walletEarliestKeyCreationTimeSecs: Long?
): Long? {
    val sentinel = Constants.EARLIEST_HD_SEED_CREATION_TIME
    val informative = listOfNotNull(
        configuredCreationDateSecs,
        walletEarliestKeyCreationTimeSecs
    ).filter { it > sentinel }
    return informative.minOrNull() ?: walletEarliestKeyCreationTimeSecs
}

/**
 * The NON-INTERACTIVE wallet-unlock recipe shared by every background
 * binding trigger — originally inlined at
 * [de.schildbach.wallet.service.platform.PlatformSynchronizationService.init],
 * extracted so the shadow-state recovery path
 * ([de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
 * .recoverByRecreatingWallet]) reuses the exact same recipe instead of
 * duplicating it.
 *
 * Recovers the wallet-crypter key the same way the platform sync loops do
 * ([PlatformRepo.getWalletEncryptionKey] — the SecurityGuard-stored
 * password, scrypt-derived, NO user prompt ever). Returns null when no
 * unlock is obtainable right now (wallet not loaded, or the password
 * retrieval failed) — callers treat that as "skip this pass, retry on the
 * next trigger". The [WalletUnlock] trust model holds: this class asserts
 * the user already authorized background platform work by setting up
 * DashPay (the same assertion the pre-extraction call sites made).
 */
@Singleton
class NonInteractiveWalletUnlock @Inject constructor(
    private val walletData: WalletData,
    private val platformRepo: PlatformRepo
) {
    /**
     * The current unlock proof, or null when unavailable. Potentially
     * expensive (scrypt key derivation) — call from a background
     * dispatcher only, and only after cheap eligibility gates have passed
     * (the [SdkWalletBinder.bindInBackground] lazy-provider contract).
     */
    suspend fun unlockOrNull(): WalletUnlock? {
        val wallet = walletData.wallet
        return when {
            wallet == null -> null
            !wallet.isEncrypted -> WalletUnlock.Unencrypted
            else -> platformRepo.getWalletEncryptionKey()?.let { WalletUnlock.EncryptionKey(it) }
        }
    }
}

/**
 * Message fragment of the SDK's mnemonic-resolver failure when a bound
 * wallet has no phrase in the Keystore-backed `WalletStorage`
 * (`DashSdkError… "mnemonic resolver: no mnemonic stored for the supplied
 * wallet_id"`, observed live from [DashSdkService.discoverIdentities]).
 * Hit when an earlier bind pass died between `createWallet` and the
 * phrase persist (locked/dozing Keystore) — message-matched until the SDK
 * exposes a typed resolver error.
 */
internal const val NO_MNEMONIC_STORED_MESSAGE = "no mnemonic stored"

/**
 * Whether [t] (or a cause within a bounded chain walk) is the
 * missing-mnemonic resolver failure — pure, host-testable.
 */
internal fun isMissingMnemonicError(t: Throwable): Boolean {
    var current: Throwable? = t
    repeat(8) {
        val cause = current ?: return false
        if (cause.message?.contains(NO_MNEMONIC_STORED_MESSAGE) == true) return true
        current = cause.cause
    }
    return false
}

/**
 * Whether the binder's bound/latched state belongs to a DIFFERENT app
 * wallet than the one currently loaded — the pure decision behind
 * [SdkWalletBinder]'s per-trigger latch revalidation (the
 * [ShadowResetDecider]-style pure-core + thin-wiring pattern).
 *
 * Live incident (S21, Reset Wallet → restore-from-seed WITHOUT a process
 * restart): the success latch kept the PREVIOUS wallet's SDK binding, so
 * every bound-wallet consumer (L1 shadow parity, shielded runtime, DashPay
 * writes) kept comparing/operating against the OLD deterministic wallet id
 * — an error class no shadow reset can fix (the parity decider hard-reset
 * in vain) and that only a manual app restart used to clear.
 *
 * [latchedFingerprint] is the app-wallet fingerprint captured when the
 * bind pass handed the seed to the SDK; [currentFingerprint] is the
 * fingerprint of the wallet loaded right now. Stale means: the current
 * wallet is fingerprintable AND it differs from what was latched —
 * including a latch captured while no wallet was fingerprintable
 * (`latchedFingerprint == null`), which cannot prove it covers the current
 * wallet. A currently-unfingerprintable wallet (`currentFingerprint ==
 * null`, e.g. momentarily unloaded) keeps the latch: there is no evidence
 * of a replacement, and the next trigger re-checks anyway.
 */
internal fun isBoundWalletStale(latchedFingerprint: String?, currentFingerprint: String?): Boolean =
    currentFingerprint != null && latchedFingerprint != currentFingerprint

/**
 * One line describing a deferred-contact-account drain pass, for EVERY
 * outcome including the two that do no work.
 *
 * Silence is not an acceptable outcome here. wallet.log is the only artifact a
 * remote tester can send back and its appender is INFO+, so a drain that
 * logged the empty and unbound cases at debug read exactly like a drain that
 * was never wired — which is what the first field report of this fix could not
 * distinguish. Every line therefore carries `queued=`, so its presence proves
 * the pass ran and its absence proves it did not.
 */
internal fun describeContactDrain(report: DashPayContactDrainReport): String = when {
    !report.bound -> "queued=n/a — SDK wallet not loaded yet, nothing attempted"
    report.queuedBefore == 0 -> "queued=0 built=0 stillQueued=0 — nothing deferred"
    else -> "queued=${report.queuedBefore} built=${report.built} " +
        "stillQueued=${report.queuedAfter} (blocked or still draining), " +
        "drainScheduled=${report.drainScheduled}"
}

/**
 * Phase 3f production wiring (`docs/kotlin-sdk-migration-plan.md`): make
 * the Kotlin-SDK wallet binding ([DashSdkService.bindAppWallet]) and
 * identity discovery ([DashSdkService.discoverIdentities]) actually happen
 * for platform users, so the flag-gated read/write paths (Phases 3c–3e)
 * become live-testable — with both flags OFF (the default) this class is
 * provably inert.
 *
 * ## What one successful pass establishes
 *
 * 1. The app's BIP39 seed is bound into the SDK
 *    ([DashSdkService.bindAppWallet] — idempotent, phrase persisted in the
 *    SDK's Keystore-backed `WalletStorage`), and
 * 2. the app's EXISTING on-chain identity (id from
 *    [BlockchainIdentityConfig], registered by dashj at identity index 0)
 *    is attached to that wallet's Rust `IdentityManager` via the DIP-9
 *    gap-limit discovery scan — after which
 *    [DashSdkService.isIdentityManaged] is true, `dashpay.syncState(id)`
 *    is non-null, and the [SdkDashPayWrites] preflight can pass; and
 * 3. the identity's PRIVATE keys are derived from the seed and stored in
 *    the SDK's Keystore-backed key store
 *    ([DashSdkService.ensureIdentityKeysSignable], Phase 3f-b) so the FFI
 *    signer can actually sign — discovery alone leaves this store empty
 *    when its auto-derive hits an expired Keystore auth window ("no
 *    private key stored for <pubkeyHex>" observed live on-device).
 *
 * ## Eligibility gate (checked in order, cheapest first)
 *
 * 1. One of [DashPayConfig.USE_KOTLIN_SDK_DPNS_READS],
 *    [DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES],
 *    [DashPayConfig.USE_KOTLIN_SDK_SHIELDED] or
 *    [DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW] is ON (re-read every
 *    call; all OFF → return before touching the SDK, the mnemonic
 *    provider, or the identity config — the inertness contract).
 * 2. [Constants.SUPPORTS_PLATFORM] (64-bit builds only).
 * 3. The app actually has (or is creating) a platform identity
 *    ([BlockchainIdentityConfig.loadBase] reports `creationState != NONE`
 *    or a stored identity id) — OR `USE_KOTLIN_SDK_SHIELDED` /
 *    `USE_KOTLIN_SDK_L1_SHADOW` is ON: shielding and the shielded-funded
 *    username creation need a bound wallet BEFORE the first identity
 *    exists, and the read-only L1 shadow scan must run on wallets with
 *    no platform identity at all, so those wallets bind too
 *    (binding-only; identity discovery defers until an id is stored).
 *
 * ## Trust model / seed hygiene
 *
 * The binder NEVER prompts and never touches `SecurityGuard`: call sites
 * pass a [WalletUnlock] (or a lazy provider of one) proving the user
 * already authorized seed access — the same contract as
 * [PlatformMnemonicProvider]. The unlock provider is invoked only AFTER
 * the eligibility gate passes (so the scrypt key derivation some
 * providers perform is never paid while the flags are off), the decrypted
 * words go straight to [DashSdkService.bindAppWallet], and no reference
 * outlives the call. Nothing seed-derived is ever logged.
 *
 * ## Concurrency / failure contract
 *
 * - **Single-flight**: a [Mutex] serializes passes; concurrent callers
 *   wait, then see the success latch and no-op.
 * - **Idempotent**: after a fully successful pass (wallet bound AND
 *   identity attached, or bound with provably nothing to attach) every
 *   later call returns at the latch. Partial progress is kept — if the
 *   bind succeeded but discovery failed (network), the next call retries
 *   discovery WITHOUT re-requesting the seed.
 * - **Never throws** (except [CancellationException]): binding is
 *   opportunistic; any failure is logged and swallowed so the calling
 *   flow (platform sync start, a DashPay broadcast) is never broken by
 *   it. dashj behavior is unchanged either way — the SDK write preflight
 *   simply keeps reporting "not bound / not managed" and falls back.
 *
 * Call sites (Phase 3f):
 * - [de.schildbach.wallet.service.platform.PlatformSynchronizationService.init]
 *   — platform work already starts here and the wallet encryption key is
 *   obtainable non-interactively (`PlatformRepo.getWalletEncryptionKey`).
 * - [de.schildbach.wallet.service.platform.PlatformDocumentBroadcastService]
 *   — the DashPay writes already hold the decrypt key; an opportunistic
 *   background (re)bind there heals a failed startup bind so the NEXT
 *   write can take the SDK path.
 */
@Singleton
class SdkWalletBinder internal constructor(
    private val sdkService: DashSdkService,
    private val mnemonicProvider: PlatformMnemonicProvider,
    private val identityConfig: BlockchainIdentityConfig,
    private val dashPayConfig: DashPayConfig,
    private val walletData: WalletData,
    // Holds the wallet creation date the user picks on the restore screen
    // (and in Settings → Rescan). See [sdkWalletBirthTimeSecs] for why the
    // dashj wallet's own earliestKeyCreationTime cannot answer this.
    private val blockchainServiceConfig: BlockchainServiceConfig,
    private val scope: CoroutineScope,
    private val supportsPlatform: () -> Boolean,
    // Injectable clock so the friend-chain provisioning throttle is
    // deterministically testable on the host JVM. Production uses wall time.
    private val now: () -> Long = { System.currentTimeMillis() },
    // Stops the DIP-15 coreHeight backfill re-firing on every launch; see
    // [DashPayBackfillGate]. Defaults to the pre-feature always-run
    // behaviour so unrelated call sites and tests are provably unaffected.
    private val backfillGate: DashPayBackfillGate = DashPayBackfillGate.ALWAYS_RUN,
    // Injectable so the post-arm rewind watch is testable on the host JVM
    // without a minute of real time per poll. Production uses the constant.
    private val backfillWatchIntervalMs: Long = BACKFILL_WATCH_INTERVAL_MS
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        mnemonicProvider: PlatformMnemonicProvider,
        identityConfig: BlockchainIdentityConfig,
        dashPayConfig: DashPayConfig,
        walletData: WalletData,
        blockchainServiceConfig: BlockchainServiceConfig,
        scope: CoroutineScope,
        backfillGate: DashPayBackfillGate
    ) : this(
        sdkService = sdkService,
        mnemonicProvider = mnemonicProvider,
        identityConfig = identityConfig,
        dashPayConfig = dashPayConfig,
        walletData = walletData,
        blockchainServiceConfig = blockchainServiceConfig,
        scope = scope,
        supportsPlatform = { Constants.SUPPORTS_PLATFORM },
        backfillGate = backfillGate
    )

    /** Serializes passes — the single-flight guarantee. */
    private val mutex = Mutex()

    /** Bound SDK wallet id (hex); survives a failed discovery so the retry skips the seed hand-off. */
    @Volatile
    private var boundWalletIdHex: String? = null

    /**
     * Fingerprint of the APP wallet whose seed the bind pass handed to the
     * SDK (captured next to [boundWalletIdHex], cleared with it). The SDK
     * wallet id is a deterministic function of the seed, so this records
     * WHICH wallet the bound id (and the [completed] latch) belong to —
     * see [currentWalletFingerprint] for what the fingerprint is and
     * [isBoundWalletStale] for how it is compared.
     */
    @Volatile
    private var boundWalletFingerprint: String? = null

    /**
     * Success latch: wallet bound, identity attached AND its keys healed
     * to a settled state (or provably nothing to attach). Deliberately NOT
     * set while a transient key-heal failure is outstanding, so the next
     * trigger retries the heal (cheap: bind + discovery are both skipped).
     *
     * Only valid FOR THE WALLET fingerprinted in [boundWalletFingerprint]:
     * every pass revalidates it against the currently-loaded app wallet
     * ([revalidateBoundWallet]) before honoring it, because the app can
     * replace its wallet IN-PROCESS (Reset Wallet → restore-from-seed) and
     * a latch surviving that replacement pins every SDK consumer to the
     * WRONG deterministic wallet id (observed live on the S21).
     */
    @Volatile
    private var completed = false

    /**
     * Single-flights the DIP-15 friend-chain provisioning pass. Distinct
     * from the bind [mutex]: provisioning does its own network I/O (a
     * DashPay sweep) and must neither block nor be blocked by a bind pass.
     */
    private val provisioning = AtomicBoolean(false)

    /** Single-flight guard for [watchArmedBackfillRewind]. */
    private val backfillWatchRunning = AtomicBoolean(false)

    /**
     * Wall-clock of the last provisioning pass — throttles the non-forced
     * heartbeat trigger (the 15 s contact ticker) so it doesn't drive a
     * network DashPay sweep every tick. Forced passes (bind completion, a
     * freshly established contact) ignore it.
     */
    @Volatile
    private var lastProvisionAtMs = 0L

    /**
     * Forget everything a previous pass established — the shadow-state
     * recovery hook ([L1ShadowSyncService.recoverByRecreatingWallet])
     * calls this right after [DashSdkService.removeAppWallet] destroyed
     * the bound SDK wallet, so the NEXT [bindIfEnabled] pass runs the
     * full first-bind path again: the mnemonic dedup finds nothing (the
     * manager holds no wallets and the phrase was deleted from
     * `WalletStorage` by the removal cascade), `createWallet` re-derives
     * the SAME deterministic wallet id from the same seed — this time
     * with the checkpoint-mapped birth height — and identity discovery +
     * key healing re-attach the app identity to the fresh wallet rows.
     *
     * Serialized under the binder [mutex] so an in-flight pass finishes
     * (against the doomed wallet — harmless, it is removed after) before
     * the latch clears; the reset itself can never interleave with a
     * half-done pass.
     */
    internal suspend fun resetForWalletRecreation() {
        mutex.withLock {
            log.warn(
                "binder state reset for SDK wallet re-creation: bound wallet id and success " +
                    "latch cleared — the next binding trigger runs a full bind + discovery pass"
            )
            boundWalletIdHex = null
            boundWalletFingerprint = null
            completed = false
        }
    }

    /**
     * A cheap fingerprint of the CURRENTLY-LOADED app wallet's seed: the
     * dashj wallet's watching key (the public account-level xpub — already
     * derived in memory, no unlock, no scrypt; the same public material
     * `WalletApplication` logs at startup). It is a deterministic function
     * of the exact seed the bind pass feeds into the SDK's deterministic
     * wallet-id derivation ([DashSdkService.bindAppWallet]), so equal
     * fingerprints ⇔ same SDK binding — a restore of the SAME phrase keeps
     * the latch (correct: the deterministic id is unchanged), any other
     * wallet invalidates it. No new key-material handling: strictly public
     * bytes, never logged beyond a prefix. Null when no wallet is loaded
     * or the wallet cannot produce a watching key (treated as "no
     * evidence" — see [isBoundWalletStale]).
     */
    private fun currentWalletFingerprint(): String? = try {
        walletData.wallet?.watchingKey?.pubKeyHash?.joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        log.warn("failed to fingerprint the app wallet; keeping the current binder state", t)
        null
    }

    /**
     * Per-trigger latch revalidation (caller holds [mutex]): if the app
     * wallet was REPLACED IN-PROCESS since the bind (Reset Wallet →
     * restore-from-seed, no process restart — the live S21 incident),
     * everything the previous pass established is for the WRONG wallet.
     * Treat as unbound: clear the bound id + latch so THIS pass runs the
     * full bind (seed hand-off, orphan prune of the old SDK wallet,
     * discovery + key heal) against the new wallet.
     *
     * Wiring note: `WalletData.observeWalletChanged()` exists and
     * could push this eagerly, but subscribing would need an
     * always-running collector from construction — breaking the binder's
     * do-nothing-until-triggered posture — and adds no coverage:
     * binding triggers already fire on every platform-sync start and every
     * DashPay broadcast, and a stale binding only matters when something
     * is about to USE it, i.e. exactly at those triggers.
     */
    private fun revalidateBoundWallet() {
        if (boundWalletIdHex == null && !completed) return // nothing latched to revalidate
        val current = currentWalletFingerprint()
        if (!isBoundWalletStale(boundWalletFingerprint, current)) return
        log.warn(
            "app wallet replaced in-process (fingerprint {}… -> {}…): dropping bound SDK " +
                "wallet {}… and the success latch — this pass re-runs the full bind " +
                "(orphan prune + creation) against the current wallet",
            boundWalletFingerprint?.take(8) ?: "none", current?.take(8),
            boundWalletIdHex?.take(8) ?: "none"
        )
        boundWalletIdHex = null
        boundWalletFingerprint = null
        completed = false
    }

    /**
     * Fire-and-forget variant for call sites that must not wait (the
     * calling flow's latency is unaffected; failures are logged inside).
     * The [unlock] proof is captured until the background pass finishes —
     * pass the wallet-crypter [WalletUnlock.EncryptionKey] (which the app
     * already keeps in scope for the duration of any platform broadcast),
     * never raw seed material.
     */
    fun bindInBackground(unlock: WalletUnlock): Job = bindInBackground { unlock }

    /** Lazy fire-and-forget variant: [unlockProvider] runs only if the eligibility gate passes. */
    fun bindInBackground(unlockProvider: suspend () -> WalletUnlock?): Job =
        scope.launch { bindIfEnabled(unlockProvider) }

    /**
     * Fire-and-forget DIP-15 friend-chain provisioning for the bound SDK
     * wallet ([DashSdkService.provisionDashPayContactAccounts]): drive the
     * SDK's contact-sync + account-drain so the SDK L1 wallet derives and
     * watches the addresses DashPay contacts pay us on — the gap the
     * dashj-only contact bookkeeping leaves (see that method's KDoc /
     * `scratchpad/txdiff/FINDINGS.md`). Gated by the SAME eligibility the
     * bind pass uses, single-flight, and throttled for non-[force] triggers.
     * Never throws into the caller.
     *
     * Independent of the bind success latch: provisioning must keep running
     * on later triggers (a drain registers accounts asynchronously, and the
     * follow-up sweep that re-scans their funding heights has to land on a
     * SUBSEQUENT pass), whereas the bind pass latches once and stops.
     *
     * @param force run even if the throttle window hasn't elapsed — use on
     *   bind completion and when a contact was just established. The
     *   single-flight guard still applies.
     */
    fun provisionContactAccountsInBackground(force: Boolean = false): Job =
        scope.launch { provisionContactAccountsIfEnabled(force) }

    /**
     * Watch for the armed rewind's durable drop until the gate has latched it.
     *
     * The gate's own logic is race-free — it recognises the rewind whenever it
     * sees the synced height below the armed target — but that is only true for
     * a bounded window, and the gate is otherwise consulted only when a
     * provisioning trigger happens to fire. On a wallet with many contacts the
     * window is minutes and the next trigger is half an hour away, so the
     * evidence expires unseen and every launch pays the full rescan again. This
     * loop closes that gap and costs one cheap signal read per minute, only
     * after a pass that actually armed a rewind, and only until it is latched.
     */
    private fun watchArmedBackfillRewind(walletId: String, identityId: ByteArray, userId: String) {
        if (!backfillWatchRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                repeat(BACKFILL_WATCH_MAX_POLLS) { poll ->
                    delay(backfillWatchIntervalMs)
                    try {
                        if (backfillGate.isRewindAccountedFor()) return@launch
                        // evaluate() persists the latch as a side effect. It
                        // cannot re-arm here: this process has provisioned, so
                        // the unaccounted-marker branch is not reachable.
                        backfillGate.evaluate(walletId, identityId, userId)
                        if (backfillGate.isRewindAccountedFor()) return@launch
                        // Still nothing accounted for after a sustained window
                        // of polling — and this loop would have exited the
                        // moment anything was. That is the evidence that no
                        // rewind is coming, which no single after-read can
                        // provide. Without concluding here, a wallet that
                        // needed no backfill re-provisions on every launch
                        // forever, because the settled-sweep path it would
                        // otherwise take is unreachable while the SDK
                        // re-enqueues every contact's account build per launch.
                        if (poll + 1 >= BACKFILL_NO_REWIND_CONCLUSION_POLLS &&
                            backfillGate.concludeNoRewindObserved(walletId, identityId, userId)
                        ) {
                            return@launch
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("DashPay backfill rewind watch poll failed; retrying", e)
                    }
                }
                log.info(
                    "DashPay backfill rewind watch gave up after {} polls; the armed marker " +
                        "stays, so the next launch provisions again",
                    BACKFILL_WATCH_MAX_POLLS
                )
            } finally {
                backfillWatchRunning.set(false)
            }
        }
    }

    /** As [provisionContactAccountsInBackground], but awaitable. Never throws. */
    suspend fun provisionContactAccountsIfEnabled(force: Boolean = false) {
        try {
            // Same posture as bind: inert unless a USE_KOTLIN_SDK_* flag is
            // on and platform is supported. Read failures are treated as off.
            if (!anyFlagEnabled() || !supportsPlatform()) return
            // A friend chain hangs off the bound wallet; until a bind pass
            // has produced a bound id there is nothing to provision (the
            // next bind trigger will, and then this).
            val walletId = boundWalletIdHex ?: return
            // Throttle non-forced heartbeat triggers BEFORE the identity read
            // so a rate-limited tick costs only the flag reads above.
            val nowMs = now()
            if (!force && nowMs - lastProvisionAtMs < PROVISION_MIN_INTERVAL_MS) return
            // No identity ⇒ no contacts ⇒ no friend chains. Mirrors the bind
            // identity gate; a config read failure falls through to "return".
            val userId = identityConfig.loadBase().userId ?: return
            // Single-flight: the sweep does network I/O and must not stack.
            if (!provisioning.compareAndSet(false, true)) return
            try {
                lastProvisionAtMs = nowMs

                // DIP-15 coreHeight-backfill gate. The sweep inside
                // provisionDashPayContactAccounts unconditionally lowers the
                // SPV synced_height (the SDK's "already backfilled" guard is
                // in-memory only), so on a wallet with many contacts every
                // launch restarts a ~25-minute re-scan and the initial sync
                // never finishes. Skip the pass entirely once the backfill
                // has PROVABLY completed and no contact has appeared since —
                // the gate logs the floor, the persisted coverage and the
                // decision. Any doubt resolves to running it.
                val identityId = try {
                    Identifier.from(userId).toBuffer()
                } catch (e: Exception) {
                    log.warn("DashPay backfill gate skipped: stored identity id is malformed")
                    null
                }
                var armedRewind = false
                if (identityId != null) {
                    val decision = backfillGate.evaluate(walletId, identityId, userId)
                    if (!decision.shouldRun) {
                        // The gate skips the SWEEP (it rewinds the SPV synced
                        // height), never the DRAIN. Those are separate: a
                        // contact's receiving account that is still queued has
                        // no addresses in the watched script set, so their
                        // payments never match a filter and the balance stays
                        // short — and before this, the queue was only ever
                        // drained on a pass the gate allowed through, i.e. on
                        // almost no launch after the first.
                        val registeredNew = drainDeferredAccountBuilds(walletId, identityId)
                        // Post-drain follow-up sweep, at most ONE per cycle:
                        // accounts this drain just REGISTERED were not there
                        // when any sweep's rescan reconcile ran, so the SDK's
                        // per-contact rescan guard has never fired for them.
                        // The gate's registration signal is set (and its
                        // coverage durably invalidated) by the note inside
                        // the drain — consult it again and give the reconcile
                        // its second look NOW, in this same cycle, instead of
                        // leaving the money invisible until a relaunch.
                        if (registeredNew) {
                            val followUp = backfillGate.evaluate(walletId, identityId, userId)
                            if (followUp.shouldRun) {
                                if (followUp.armedToWrite != null) armedRewind = true
                                log.info(
                                    "DashPay follow-up sweep on {}…: the drain registered " +
                                        "receival account(s) no sweep's rescan reconcile has " +
                                        "seen — sweeping again in this cycle",
                                    walletId.take(8)
                                )
                                runProvisioningSweep(walletId, identityId)
                            } else {
                                // e.g. a backfill replay in flight, which a
                                // registration must never interrupt. The debt
                                // is recorded (durably invalidated coverage +
                                // the in-memory signal) for a later pass.
                                log.info(
                                    "DashPay follow-up sweep withheld on {}…: {} — the " +
                                        "registration debt stays recorded for a later pass",
                                    walletId.take(8), followUp.reason
                                )
                            }
                        }
                        if (armedRewind) {
                            watchArmedBackfillRewind(walletId, identityId, userId)
                        }
                        return
                    }
                    armedRewind = decision.armedToWrite != null
                }

                val registrationOutstanding = runProvisioningSweep(walletId, identityId)
                // Post-drain follow-up sweep (run path), at most ONE per
                // cycle: this pass's own step-2 drain registered receival
                // accounts AFTER its sweep reconciled the rewind — the exact
                // ordering defect behind the restored-wallet dark contacts
                // (sweep at 17:20:08 against zero accounts, 29 registered
                // 17:20:11–31, no rewind until a manual restart). The gate's
                // registration branches turn the signal recordPassOutcome
                // re-raised into a permitted, re-armed pass.
                if (registrationOutstanding && identityId != null) {
                    val followUp = backfillGate.evaluate(walletId, identityId, userId)
                    if (followUp.shouldRun) {
                        if (followUp.armedToWrite != null) armedRewind = true
                        log.info(
                            "DashPay follow-up sweep on {}…: this pass's drain registered " +
                                "receival account(s) after its sweep reconciled — sweeping " +
                                "again in this cycle",
                            walletId.take(8)
                        )
                        runProvisioningSweep(walletId, identityId)
                    }
                }
                // The pass we just armed rewinds the SPV synced height, but the
                // drop only becomes DURABLE ~9-60 s later, and it stays visible
                // only until the scan climbs back out of it. recordPassOutcome
                // reads once, immediately, and routinely loses that race; the
                // next consultation is driven by the provisioning trigger, which
                // in the field arrived ~32 min later — long after the evidence
                // was gone. The marker then went unaccounted for and the NEXT
                // launch re-ran the whole rewind, forever. Poll for it instead.
                if (armedRewind && identityId != null) {
                    watchArmedBackfillRewind(walletId, identityId, userId)
                }
            } finally {
                provisioning.set(false)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Opportunistic by contract: dashj funds discovery is unaffected.
            log.warn(
                "DashPay friend-chain provisioning pass failed; SDK contact-payment " +
                    "discovery may lag until the next pass",
                t
            )
        }
    }

    /**
     * One provisioning pass — the SDK sweep + drain
     * ([DashSdkService.provisionDashPayContactAccounts]) with its gate
     * accounting, wallet.log lines and post-drain receival-coverage
     * diagnostics. Throws freely; the caller
     * ([provisionContactAccountsIfEnabled]) contains the fallout.
     *
     * @return whether the pass's own drain left a registration OUTSTANDING —
     *   the gate's signal, consumed and then re-raised from the pass's built
     *   count by [DashPayBackfillGate.recordPassOutcome] — i.e. whether a
     *   follow-up sweep is owed. Always false without an identity, and with
     *   a gate that records nothing ([DashPayBackfillGate.ALWAYS_RUN]).
     */
    private suspend fun runProvisioningSweep(walletId: String, identityId: ByteArray?): Boolean {
        val report = sdkService.provisionDashPayContactAccounts(walletId)
        identityId?.let { backfillGate.recordPassOutcome(walletId, it, report) }
        // The sweep is a long native op: its SDK lines sat in the
        // logcat buffer until the bridge's next 30 s / 5 min poll and
        // were routinely rolled over before then. Pull them into
        // wallet.log NOW, while they are still there — cheap,
        // bounded, never throws, and we are on a background
        // coroutine, not the main thread.
        NativeLogBridge.drainNow()
        when {
            !report.bound -> log.debug(
                "DashPay friend-chain provisioning: SDK wallet {}… not loaded yet",
                walletId.take(8)
            )
            report.pendingBefore > 0 || report.drainScheduled -> log.info(
                "DashPay friend-chain provisioning on {}…: sweep ok={}/err={}, " +
                    "{} account build(s) queued, drainScheduled={}",
                walletId.take(8), report.syncSuccess, report.syncErrors,
                report.pendingBefore, report.drainScheduled
            )
            else -> log.debug(
                "DashPay friend-chain provisioning on {}…: steady " +
                    "(sweep ok={}/err={}, nothing queued)",
                walletId.take(8), report.syncSuccess, report.syncErrors
            )
        }
        if (identityId != null && report.bound) {
            logReceivalCoverageDiagnostics(walletId, identityId)
        }
        return identityId != null && backfillGate.readBackfillStatus().registrationOutstanding
    }

    /**
     * The dark-contact diagnostic, one line per drain: how many established
     * contacts have NO receival account. Such a contact's receiving addresses
     * are in no watched script set, and under the SDK's re-enqueue asymmetry
     * a build that keeps failing is re-enqueued forever without ever
     * registering — so a delta that persists across drains is the fingerprint
     * of a PERMANENTLY dark contact, and the sampled ids let a field log name
     * exactly which one is stuck. Never throws; an unavailable read logs
     * nothing (the drain line above it already proves the pass ran).
     */
    private suspend fun logReceivalCoverageDiagnostics(walletId: String, identityId: ByteArray) {
        try {
            val coverage = sdkService.readDashPayReceivalCoverage(walletId, identityId) ?: return
            val sample = if (coverage.darkContactIdSample.isEmpty()) {
                ""
            } else {
                coverage.darkContactIdSample.joinToString(
                    prefix = " [", separator = ", ",
                    postfix = if (coverage.darkContacts > coverage.darkContactIdSample.size) ", …]" else "]"
                ) { "${it.take(8)}…" }
            }
            log.info(
                "DashPay receival-account coverage on {}…: establishedContacts={}, " +
                    "receivalAccounts={}, dark={}{} — a dark contact's receiving addresses " +
                    "are in no watched script set (permanently-dark candidate under the SDK's " +
                    "re-enqueue asymmetry)",
                walletId.take(8), coverage.establishedContacts, coverage.receivalAccounts,
                coverage.darkContacts, sample
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.debug("receival-coverage diagnostics unavailable: {}", t.message)
        }
    }

    /**
     * Drain the SDK's deferred DashPay account-build queue and say what
     * happened — queued / built / still queued. The counts are the only view
     * the app has of that queue: the SDK logs an enqueue per contact
     * ("Deferred DashPay account build: enqueued for the signer-backed
     * drain") and then nothing, so a queue that never drains is invisible
     * after the session that filled it.
     *
     * Reports on EVERY pass, at INFO, including the empty and unbound cases
     * ([describeContactDrain]) — wallet.log's appender is INFO+, so the
     * earlier debug-level "queue empty" / "not loaded yet" branches were
     * indistinguishable from the drain never running at all, which is exactly
     * the question a tester's log has to answer.
     *
     * Never throws: an unavailable drain (locked device, seed verify) is a
     * normal state and the queue survives for the next pass.
     *
     * @return whether the drain's registrations were accepted as NEW by the
     *   gate ([DashPayBackfillGate.noteAccountBuildsRegistered] — which has
     *   also durably invalidated any recorded coverage by the time it
     *   answers), i.e. whether a follow-up sweep is owed in THIS cycle.
     *   False for an empty/muted/failed drain.
     */
    private suspend fun drainDeferredAccountBuilds(walletId: String, identityId: ByteArray): Boolean {
        return try {
            val report = sdkService.drainDashPayContactAccountBuilds(walletId)
            log.info(
                "DashPay account-build drain on {}…: {}",
                walletId.take(8), describeContactDrain(report)
            )
            // Accounts that only exist NOW were not there when the last sweep
            // reconciled the DIP-15 rewind, so a sweep is owed for them; the
            // gate records that debt durably (coverage invalidation) and its
            // verdict lets THIS cycle pay it with a follow-up sweep instead
            // of leaving the money invisible until a relaunch.
            val registeredNew = backfillGate.noteAccountBuildsRegistered(report.built)
            if (report.bound) {
                logReceivalCoverageDiagnostics(walletId, identityId)
            }
            registeredNew
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn(
                "DashPay account-build drain failed; the contacts' receiving addresses stay " +
                    "unwatched until the next pass",
                t
            )
            false
        }
    }

    /**
     * Bind the app wallet into the SDK and attach its identity, if (and
     * only if) the eligibility gate passes. Never throws (see class KDoc).
     *
     * @param unlock proof of an already-completed wallet unlock; the
     *   caller owns authentication ([WalletUnlock] trust model).
     */
    suspend fun bindIfEnabled(unlock: WalletUnlock) = bindIfEnabled { unlock }

    /**
     * As [bindIfEnabled], but the unlock is produced lazily — only after
     * the flag/platform/identity eligibility checks pass — so providers
     * that derive the wallet key (scrypt, ~seconds) cost nothing while
     * the feature flags are off. A null from [unlockProvider] means "no
     * unlock available right now"; the pass is skipped and retried on the
     * next trigger.
     */
    suspend fun bindIfEnabled(unlockProvider: suspend () -> WalletUnlock?) {
        try {
            mutex.withLock {
                // In-process wallet replacement check BEFORE honoring the
                // latch — a latch for a replaced wallet is worse than no
                // latch (it pins every consumer to the wrong SDK wallet).
                revalidateBoundWallet()
                if (completed) return
                try {
                    bindLocked(unlockProvider)
                } catch (t: Throwable) {
                    if (t !is CancellationException) noteMissingMnemonic(t)
                    throw t
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Opportunistic by contract: never break the calling flow.
            log.warn("SDK wallet binding pass failed; dashj behavior unchanged", t)
        }
    }

    /**
     * "no mnemonic stored" from any post-bind step means the bound SDK
     * wallet has no phrase in `WalletStorage` (an earlier bind pass died
     * between `createWallet` and the phrase persist). Retrying discovery
     * against that wallet can never succeed — the re-store lives in
     * [DashSdkService.bindAppWallet]'s already-exists recovery — so drop
     * the cached bound id, forcing the NEXT trigger through the full bind
     * pass (seed hand-off + mnemonic re-store) instead of re-failing
     * forever. The pass itself stays failed-but-retryable, never latched.
     * Caller holds [mutex].
     */
    private fun noteMissingMnemonic(t: Throwable) {
        if (!isMissingMnemonicError(t)) return
        val bound = boundWalletIdHex ?: return
        boundWalletIdHex = null
        boundWalletFingerprint = null
        log.warn(
            "SDK wallet {}… has no stored mnemonic — clearing the cached bind so the next " +
                "binding trigger re-runs the full bind pass (which re-stores the phrase)",
            bound.take(8)
        )
    }

    /**
     * The birth time handed to [DashSdkService.bindAppWallet]: the wallet
     * creation date the user picked during the restore (persisted in
     * [BlockchainServiceConfig], the same store dashj's own checkpointing
     * reads) combined with the dashj wallet's key time by
     * [sdkWalletBirthTimeSecs]. A config read failure degrades to the
     * wallet time alone — never to a value LATER than what we would
     * otherwise have used.
     */
    private suspend fun resolveBirthTimeSecs(): Long? {
        val configured = try {
            blockchainServiceConfig.getWalletCreationDate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("wallet creation date unreadable; falling back to the wallet's key time", e)
            null
        }
        return sdkWalletBirthTimeSecs(configured, walletData.wallet?.earliestKeyCreationTime)
    }

    /** One pass; caller holds [mutex]. Throws freely — [bindIfEnabled] contains the fallout. */
    private suspend fun bindLocked(unlockProvider: suspend () -> WalletUnlock?) {
        // 1. Flags (both default OFF). Read failure = off.
        if (!anyFlagEnabled()) return

        // 2. Platform support is fixed per device/build.
        if (!supportsPlatform()) {
            log.info("SDK binding skipped: platform not supported on this device")
            return
        }

        // 3. Platform-user check: wire wallets that have (or are creating)
        //    an identity — OR any wallet when the shielded features are on,
        //    because shielding and the shielded-funded username creation
        //    both need a bound wallet BEFORE the first identity exists
        //    (the fresh-wallet path: fund → shield → create from pool).
        //    The L1 shadow widens the gate the same way: the shadow scan
        //    is read-only parity instrumentation over the bound seed and
        //    must run on wallets with no platform identity at all (the
        //    mainnet validation case: shadow flag on, everything else off).
        val identity = identityConfig.loadBase()
        val hasPlatformIdentity =
            identity.creationState != IdentityCreationState.NONE || identity.userId != null
        if (!hasPlatformIdentity && !shieldedFlagEnabled() && !l1ShadowFlagEnabled()) {
            log.info("SDK binding skipped: no platform identity on this wallet and shielded/L1-shadow features off")
            return
        }

        // 4. Bind the seed (skipped when a previous pass already bound it).
        val walletId = boundWalletIdHex ?: run {
            val unlock = unlockProvider()
            if (unlock == null) {
                log.info("SDK binding skipped: no wallet unlock available at this call site")
                return
            }
            // Record WHICH wallet this pass binds (same wallet the mnemonic
            // provider reads the seed from) so later triggers can detect an
            // in-process wallet replacement — see revalidateBoundWallet().
            val fingerprint = currentWalletFingerprint()
            // Decrypt + hand off; the words are function-local and the
            // reference dies with this call (see PlatformMnemonicProvider).
            val words = mnemonicProvider.getMnemonicWords(unlock)
            val birthTimeSecs = resolveBirthTimeSecs()
            sdkService.bindAppWallet(words, birthTimeSecs).also {
                boundWalletIdHex = it
                boundWalletFingerprint = fingerprint
            }
        }

        // 4b. Prune orphan SDK wallets. The app-side Reset Wallet clears the
        // app's own stores but NOT the SDK's Rust-side persistence, so after
        // a reset the manager can hold the OLD wallet next to the new one —
        // and every `singleOrNull()`-based bound-wallet lookup (shielded
        // runtime, L1 shadow, DashPay writes) then returns null forever
        // ("app wallet not bound to the SDK yet" observed live post-reset on
        // the S21). An orphan's seed no longer exists in this app, so it is
        // unusable by definition; removing it (removeWallet cascade) is the
        // self-heal. Best-effort: a prune failure must not fail the bind.
        pruneOrphanSdkWallets(walletId)

        // 4c. One-shot migration heal: widen the SDK's address-pool windows
        // to the Rust max and invalidate the recorded backfill coverage so
        // the next gate pass REWINDS and re-matches history against the
        // widened script set. Heals the migrated-wallet frontier gap:
        // dashj (or any same-seed client) spending past the SDK's derived
        // window made the change — and every descendant transaction —
        // invisible to the scan. Best-effort and re-tried on the next bind
        // until it succeeds once; must never fail the bind.
        maybeWidenAddressWindows(walletId)

        // 5. Attach the existing on-chain identity so the SDK can derive
        //    its keys (identity index 0 — key-parity note in SdkDashPayWrites).
        val userId = identity.userId
        if (userId == null) {
            // Identity creation in flight but nothing registered on chain
            // yet — binding-only for now; a later pass (creation sets the
            // id) completes the attach. Deliberately NOT latched.
            log.info("SDK wallet bound; identity not yet registered — discovery deferred")
            return
        }
        val identityId = try {
            Identifier.from(userId).toBuffer()
        } catch (e: Exception) {
            log.warn("SDK identity discovery skipped: stored identity id is malformed")
            return
        }

        if (sdkService.isIdentityManaged(walletId, identityId)) {
            log.info("SDK wallet {}… already manages the app identity", walletId.take(8))
            // Managed is necessary but NOT sufficient: an earlier discovery
            // pass may have attached the identity while the Keystore auth
            // window was expired, leaving zero stored private keys (the
            // "no private key stored" FFI signing failure seen live). Heal
            // before latching so a later pass retries a transient failure.
            completed = healIdentityKeys(walletId, identityId)
            return
        }

        // Full rescan from identity index 0 — the only index dashj creates.
        val found = sdkService.discoverIdentities(walletId, startIndex = 0)
        if (sdkService.isIdentityManaged(walletId, identityId)) {
            log.info(
                "SDK identity discovery attached the app identity to wallet {}… " +
                    "({} identity(ies) discovered)",
                walletId.take(8), found.size
            )
            // Discovery persists the PUBLIC keys; the private halves only
            // become signable once derived+stored (Phase 3f-b). Latch only
            // when nothing retryable is left.
            completed = healIdentityKeys(walletId, identityId)
        } else {
            // Scan ran but our identity wasn't on the probed keys — e.g. a
            // dashj identity whose registered master key doesn't match the
            // DIP-9 slot-0 derivation. Not retried in-process (the scan is
            // deterministic); logged for the Phase 3f live-test report.
            log.warn(
                "SDK identity discovery did NOT find the app identity {}… on wallet {}… " +
                    "({} other identity(ies) discovered); SDK writes will keep falling back to dashj",
                userId.take(8), walletId.take(8), found.size
            )
            completed = true
        }
    }

    /**
     * Phase 3f-b: make the attached identity's private keys signable
     * ([DashSdkService.ensureIdentityKeysSignable] — the example app's
     * key-health Repair flow, run automatically). Returns whether the
     * binder may latch: true when the heal is settled (every key signable
     * or provably watch-only — deterministic, so retrying is pointless),
     * false on transient failures (expired Keystore auth window, FFI or
     * Room hiccup) so the NEXT trigger — e.g. the broadcast-service rebind
     * that runs right before a DashPay write — retries the heal without
     * re-running discovery. Never throws (binding stays non-fatal).
     */
    private suspend fun healIdentityKeys(walletId: String, identityId: ByteArray): Boolean = try {
        val report = sdkService.ensureIdentityKeysSignable(walletId, identityId)
        when {
            report.allSignable -> log.info(
                "app identity keys signable on SDK wallet {}… ({} healthy, {} repaired); " +
                    "SDK write preflight can now pass",
                walletId.take(8), report.healthy, report.repaired
            )
            report.settled -> log.warn(
                "app identity attached to SDK wallet {}… but {} of {} key(s) are watch-only " +
                    "(not derivable from this seed); SDK writes signing with those keys will " +
                    "fall back to dashj",
                walletId.take(8), report.watchOnly, report.keysChecked
            )
            else -> log.warn(
                "app identity key heal incomplete on SDK wallet {}… " +
                    "({} checked, {} failed transiently); will retry on the next binding trigger",
                walletId.take(8), report.keysChecked, report.failed
            )
        }
        report.settled
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        // A missing-mnemonic failure here needs the same full-rebind
        // treatment as one from discovery (heal derives via the resolver).
        noteMissingMnemonic(t)
        log.warn("app identity key heal failed; will retry on the next binding trigger", t)
        false
    }

    /** See step 4b in [bindLocked]. Never throws. */
    private suspend fun pruneOrphanSdkWallets(currentWalletId: String) {
        val orphans = try {
            sdkService.loadedWalletIds().filter { it != currentWalletId }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("orphan-wallet scan failed; skipping prune", t)
            return
        }
        for (orphan in orphans) {
            try {
                sdkService.removeAppWallet(orphan)
                log.warn(
                    "removed orphan SDK wallet {}… left behind by an earlier app wallet " +
                        "(reset/wipe does not clear SDK-side persistence)",
                    orphan.take(8)
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to remove orphan SDK wallet {}…; will retry next bind pass", orphan.take(8), t)
            }
        }
    }

    /**
     * Step 4c — the one-shot migration address-window heal.
     *
     * Widens the SDK wallet's standard-family gap limits to the Rust max
     * ([DashSdkService.widenAddressWindows]) and, on success, invalidates
     * the recorded DIP-15 backfill coverage so the gate's next consult
     * forces a full rewind: the re-scan then matches history against the
     * widened script set, recovering transactions whose addresses sat past
     * the old window (the same-seed-client frontier gap observed in the
     * field). Guarded by [DashPayConfig.SDK_GAP_WIDENED_VERSION] so the
     * widening + forced rewind happen ONCE per heal version, not per
     * launch; a failed attempt records nothing and retries on the next
     * bind. Never throws — the bind must survive this step failing.
     */
    private suspend fun maybeWidenAddressWindows(walletIdHex: String) {
        try {
            val done = dashPayConfig.get(DashPayConfig.SDK_GAP_WIDENED_VERSION) ?: 0
            if (done >= GAP_WIDEN_HEAL_VERSION) return
            if (!sdkService.widenAddressWindows(walletIdHex)) {
                log.warn("address-window heal did not complete; will retry next bind")
                return
            }
            // Retroactivity: rewind the SPV filter watermark to the wallet's
            // birth DIRECTLY, not only via the DashPay backfill gate — the
            // gate's rewind rides the contact-provisioning pass, which never
            // runs on a wallet without a platform identity, and the frontier
            // gap does not require one. Double-arming on identity wallets is
            // harmless: the Rust side never moves the watermark forward.
            if (!sdkService.armSpvRescan(walletIdHex, resolveBirthTimeSecs())) {
                log.warn("address-window heal: rescan arm failed; will retry next bind")
                return
            }
            // Invalidate the coverage record LAST, only after the windows
            // provably widened: the forced rewind is only worth its ~full
            // re-scan when the wider script set is in place to profit.
            dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_COVERED_FLOOR)
            dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_COMPLETED_THROUGH)
            dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_CONTACT_FINGERPRINT)
            dashPayConfig.remove(DashPayConfig.DASHPAY_BACKFILL_COVERAGE_OBSERVED)
            dashPayConfig.set(DashPayConfig.SDK_GAP_WIDENED_VERSION, GAP_WIDEN_HEAL_VERSION)
            log.info(
                "address-window heal v{} applied on {}…: gaps widened, backfill coverage " +
                    "invalidated — next gate pass rewinds with the widened script set",
                GAP_WIDEN_HEAL_VERSION, walletIdHex.take(8)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("address-window heal failed; will retry next bind", t)
        }
    }

    // ── Bounded identity-discovery retry (restore safety net) ─────────

    /** Attempts made by [maybeRetryIdentityDiscovery] this process. */
    @Volatile
    private var discoveryRetryAttempts = 0

    /** Wall-clock ms before which [maybeRetryIdentityDiscovery] must not fire again. */
    @Volatile
    private var discoveryRetryNextAtMs = 0L

    /** One exhaustion WARN per process, not one per sync tick. */
    @Volatile
    private var discoveryRetryExhaustedLogged = false

    /** Latched once the identity is observed managed — the retry loop's clean stop. */
    @Volatile
    private var discoveryRetrySettled = false

    /**
     * Bounded app-side retry for a FAILED registration-time identity
     * discovery — the restore incident's safety net.
     *
     * The Rust wallet registration runs a best-effort identity sync whose
     * failure is logged and swallowed ("Identity discovery failed during
     * wallet registration; callers can retry via
     * PlatformWallet::identity().discover()"). On the field restore it
     * failed with "External signable wallet has no private key" — the
     * signer was not attached yet at registration time — and NOTHING ever
     * retried: the app then polled REQUESTED_NAME_CHECKING for 44 minutes
     * with no contact sync, because the SDK wallet never managed the
     * identity. (The bind pass's own discovery can latch [completed] on a
     * not-found scan, so binding triggers do not recover this state
     * either.)
     *
     * Called on every platform-sync pass ([PlatformSyncService.updateContactRequests]).
     * Fires only while ALL of:
     * - a bind pass has handed the seed to the SDK ([boundWalletIdHex] set
     *   — the post-unlock condition: the mnemonic resolver / signer the
     *   registration-time discovery lacked is attached now);
     * - the app has a stored identity id;
     * - the SDK wallet does NOT manage it (the restored-but-undiscovered
     *   state);
     * - fewer than [DISCOVERY_RETRY_MAX_ATTEMPTS] attempts were made, and
     *   the capped-exponential backoff window has elapsed.
     *
     * Each attempt re-runs the full [DashSdkService.discoverIdentities]
     * scan from index 0 and, on attach, heals the identity keys the same
     * way the bind pass does. Bounded on purpose: a genuinely-absent
     * identity (deterministic scan miss) stops costing Platform queries
     * after the cap — the boundedness IS the permanent-error stop, since
     * the app cannot reliably tell a signer-gap miss from a real one.
     * Never throws; a thrown scan counts as an attempt and retries on the
     * next pass. Surgical safety net until the Rust-side ordering fix
     * (attach the signer before the registration-time sync) lands.
     *
     * @return whether THIS call attached the identity — the caller's cue to
     *   drive the work that was blocked on it (contact sync) immediately
     *   rather than on the next 15 s tick. False for every no-op, every
     *   still-unmanaged attempt and every failure.
     */
    suspend fun maybeRetryIdentityDiscovery(): Boolean {
        try {
            if (discoveryRetrySettled) return false
            val walletId = boundWalletIdHex ?: return false
            val userId = identityConfig.loadBase().userId ?: return false
            val identityId = try {
                Identifier.from(userId).toBuffer()
            } catch (e: Exception) {
                return false // malformed stored id — nothing a retry can do
            }
            if (sdkService.isIdentityManaged(walletId, identityId)) {
                discoveryRetrySettled = true
                if (discoveryRetryAttempts > 0) {
                    log.info("identity-discovery retry: identity now managed; standing down")
                }
                return false
            }
            if (discoveryRetryAttempts >= DISCOVERY_RETRY_MAX_ATTEMPTS) {
                if (!discoveryRetryExhaustedLogged) {
                    discoveryRetryExhaustedLogged = true
                    log.warn(
                        "identity-discovery retry exhausted after {} attempts: the SDK wallet " +
                            "still does not manage the app identity — SDK DashPay/contact " +
                            "features stay degraded until the next process start",
                        DISCOVERY_RETRY_MAX_ATTEMPTS
                    )
                }
                return false
            }
            if (now() < discoveryRetryNextAtMs) return false
            // A bind pass owns the mutex while it runs its own discovery —
            // don't pile a concurrent scan on top; the next sync tick re-checks.
            if (!mutex.tryLock()) return false
            try {
                val attempt = ++discoveryRetryAttempts
                discoveryRetryNextAtMs = now() + discoveryRetryDelayMs(attempt)
                log.info(
                    "identity-discovery retry {}/{}: registration-time discovery failed and the " +
                        "SDK wallet {}… does not manage identity {}…; re-running the scan " +
                        "(signer/resolver attached now)",
                    attempt, DISCOVERY_RETRY_MAX_ATTEMPTS, walletId.take(8), userId.take(8)
                )
                val found = sdkService.discoverIdentities(walletId, startIndex = 0)
                if (sdkService.isIdentityManaged(walletId, identityId)) {
                    log.info(
                        "identity-discovery retry {} SUCCEEDED: identity attached to wallet {}… " +
                            "({} identity(ies) discovered); healing keys",
                        attempt, walletId.take(8), found.size
                    )
                    healIdentityKeys(walletId, identityId)
                    discoveryRetrySettled = true
                    return true
                } else {
                    log.warn(
                        "identity-discovery retry {}/{}: scan ran ({} identity(ies)) but the app " +
                            "identity is still not managed; next attempt in {}ms",
                        attempt, DISCOVERY_RETRY_MAX_ATTEMPTS, found.size,
                        discoveryRetryNextAtMs - now()
                    )
                }
            } finally {
                mutex.unlock()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("identity-discovery retry attempt failed; will retry on a later sync pass", t)
        }
        return false
    }

    /**
     * Capped exponential backoff between retry attempts: 30s, 1m, 2m, 4m,
     * 8m, then capped at [DISCOVERY_RETRY_MAX_DELAY_MS] — rides the 15s
     * platform-sync ticker, so the real spacing is "first tick after the
     * window opens". Eight attempts span roughly the 44-minute stall the
     * field incident sat in.
     */
    private fun discoveryRetryDelayMs(attempt: Int): Long =
        (DISCOVERY_RETRY_BASE_DELAY_MS shl (attempt - 1).coerceIn(0, 20))
            .coerceAtMost(DISCOVERY_RETRY_MAX_DELAY_MS)

    /**
     * The SDK half of the user-facing "Reset/Rescan blockchain" action.
     *
     * Post-cutover the reset's dashj work (delete the SPV block/header
     * stores, clear the display DB) is an SDK NO-OP: the engine keeps its
     * `WalletEntity.syncedHeight` watermark and stays SYNCED, so the user
     * sees nothing happen (field 13:10:04Z on the mainnet device).
     * This arms the SAME SPV filter-watermark rewind the address-window
     * heal uses ([DashSdkService.armSpvRescan]) so the reset actually
     * replays SDK history — from the persisted wallet-creation date when
     * the user picked one on the rescan dialog ([resolveBirthTimeSecs]
     * reads the store `SettingsFragment` just wrote), else the wallet's
     * birth. The arm also starts the last-known-balance persist hold
     * ([DashSdkService.spvRescanArmedWithin]), so a mid-replay partial
     * figure can never be persisted as the launch seed.
     *
     * Uses the binder's bound wallet id when a bind pass latched one this
     * process, else the single loaded SDK wallet. Returns whether the arm
     * ran; never throws.
     */
    suspend fun armSpvRescanForBlockchainReset(): Boolean = try {
        val walletIdHex = boundWalletIdHex ?: sdkService.loadedWalletIds().singleOrNull()
        if (walletIdHex == null) {
            log.info("blockchain-reset rescan skipped: no SDK wallet bound or loaded")
            false
        } else {
            val armed = sdkService.armSpvRescan(walletIdHex, resolveBirthTimeSecs())
            log.info(
                "blockchain-reset rescan arm on {}…: armed={}",
                walletIdHex.take(8), armed
            )
            armed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log.warn("blockchain-reset rescan arm failed", t)
        false
    }

    private suspend fun anyFlagEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) == true ||
            dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) == true ||
            dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true ||
            // The L1 shadow scan needs a bound wallet (L1ShadowSyncService's
            // "requires a bound wallet" contract) — a shadow flag that can't
            // cause a bind would silently never run when it is the ONLY
            // flag on (the read-only mainnet validation configuration).
            dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) == true
    } catch (e: Exception) {
        log.warn("failed to read Kotlin-SDK flags; treating as off", e)
        false
    }

    /** Shielded features widen the identity gate (fresh wallets bind too). Read failure = off. */
    private suspend fun shieldedFlagEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_SHIELDED; treating as off", e)
        false
    }

    /** The L1 shadow widens the identity gate too (binding-only, read-only scan). Read failure = off. */
    private suspend fun l1ShadowFlagEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_L1_SHADOW; treating as off", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkWalletBinder::class.java)

        /**
         * Version of the one-shot address-window heal
         * ([maybeWidenAddressWindows]). Bump to re-run the widening + the
         * forced coverage rewind on wallets that already healed at a lower
         * version. v2 = v1 + the direct SPV watermark rewind
         * ([DashSdkService.armSpvRescan]) — v1 relied on the DashPay
         * backfill gate for retroactivity, which identity-less wallets
         * never run (v1 shipped only in local QA 11.10.78).
         */
        internal const val GAP_WIDEN_HEAL_VERSION = 2

        /**
         * Bounds for [maybeRetryIdentityDiscovery]. Eight attempts on a
         * 30s-base capped-exponential backoff span ~45 min — sized to the
         * field stall (44 min of REQUESTED_NAME_CHECKING with no contact
         * sync after the registration-time discovery failure). The cap is
         * the permanent stop: the app cannot reliably distinguish a
         * signer-gap miss from a genuinely-absent identity, so it stops
         * paying Platform queries instead of classifying errors.
         */
        internal const val DISCOVERY_RETRY_MAX_ATTEMPTS = 8
        internal const val DISCOVERY_RETRY_BASE_DELAY_MS = 30_000L
        internal const val DISCOVERY_RETRY_MAX_DELAY_MS = 10 * 60_000L

        /**
         * Cadence and budget for [watchArmedBackfillRewind]. The window the
         * watch has to catch is bounded by how long the re-scan takes to climb
         * back past the armed height — ~15 min on the field wallet that
         * exposed this (211 contacts, ~345k filters). 45 one-minute polls
         * covers that with room to spare on a slower device, and each poll is
         * a single cached signal read.
         */
        internal const val BACKFILL_WATCH_INTERVAL_MS = 60_000L
        internal const val BACKFILL_WATCH_MAX_POLLS = 45

        /**
         * Polls of quiet observation before the watch concludes the armed pass
         * needed no rewind. Must comfortably exceed how long the SDK takes to
         * make a rewind durable — 9–60 s by its own accounting, 102 s measured
         * on the wallet this was diagnosed against — because concluding early
         * would record coverage over a range that is about to be rewound.
         */
        internal const val BACKFILL_NO_REWIND_CONCLUSION_POLLS = 5

        /**
         * Floor between non-forced friend-chain provisioning passes. The
         * contact ticker fires every 15 s; a full DashPay sweep every tick
         * would waste network, but the pass must run often enough that the
         * post-drain rescan follow-up lands promptly. One minute balances
         * both — forced passes (bind completion, a new contact) bypass it,
         * and the underlying sweep is high-water-cursor incremental so
         * steady-state passes are cheap.
         */
        internal const val PROVISION_MIN_INTERVAL_MS = 60_000L
    }
}
