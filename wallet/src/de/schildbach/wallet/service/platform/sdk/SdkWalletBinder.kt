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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

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
    private val walletData: WalletDataProvider,
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
 *    [DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES] or
 *    [DashPayConfig.USE_KOTLIN_SDK_SHIELDED] is ON (re-read every
 *    call; all OFF → return before touching the SDK, the mnemonic
 *    provider, or the identity config — the inertness contract).
 * 2. [Constants.SUPPORTS_PLATFORM] (64-bit builds only).
 * 3. The app actually has (or is creating) a platform identity
 *    ([BlockchainIdentityConfig.loadBase] reports `creationState != NONE`
 *    or a stored identity id) — OR `USE_KOTLIN_SDK_SHIELDED` is ON:
 *    shielding and the shielded-funded username creation need a bound
 *    wallet BEFORE the first identity exists, so fresh wallets bind too
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
    private val walletData: WalletDataProvider,
    private val scope: CoroutineScope,
    private val supportsPlatform: () -> Boolean
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        mnemonicProvider: PlatformMnemonicProvider,
        identityConfig: BlockchainIdentityConfig,
        dashPayConfig: DashPayConfig,
        walletData: WalletDataProvider,
        scope: CoroutineScope
    ) : this(
        sdkService = sdkService,
        mnemonicProvider = mnemonicProvider,
        identityConfig = identityConfig,
        dashPayConfig = dashPayConfig,
        walletData = walletData,
        scope = scope,
        supportsPlatform = { Constants.SUPPORTS_PLATFORM }
    )

    /** Serializes passes — the single-flight guarantee. */
    private val mutex = Mutex()

    /** Bound SDK wallet id (hex); survives a failed discovery so the retry skips the seed hand-off. */
    @Volatile
    private var boundWalletIdHex: String? = null

    /**
     * Success latch: wallet bound, identity attached AND its keys healed
     * to a settled state (or provably nothing to attach). Deliberately NOT
     * set while a transient key-heal failure is outstanding, so the next
     * trigger retries the heal (cheap: bind + discovery are both skipped).
     */
    @Volatile
    private var completed = false

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
            completed = false
        }
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
        log.warn(
            "SDK wallet {}… has no stored mnemonic — clearing the cached bind so the next " +
                "binding trigger re-runs the full bind pass (which re-stores the phrase)",
            bound.take(8)
        )
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
        val identity = identityConfig.loadBase()
        val hasPlatformIdentity =
            identity.creationState != IdentityCreationState.NONE || identity.userId != null
        if (!hasPlatformIdentity && !shieldedFlagEnabled()) {
            log.info("SDK binding skipped: no platform identity on this wallet and shielded features off")
            return
        }

        // 4. Bind the seed (skipped when a previous pass already bound it).
        val walletId = boundWalletIdHex ?: run {
            val unlock = unlockProvider()
            if (unlock == null) {
                log.info("SDK binding skipped: no wallet unlock available at this call site")
                return
            }
            // Decrypt + hand off; the words are function-local and the
            // reference dies with this call (see PlatformMnemonicProvider).
            val words = mnemonicProvider.getMnemonicWords(unlock)
            val birthTimeSecs = walletData.wallet?.earliestKeyCreationTime
            sdkService.bindAppWallet(words, birthTimeSecs).also { boundWalletIdHex = it }
        }

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

    private suspend fun anyFlagEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) == true ||
            dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) == true ||
            dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
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

    companion object {
        private val log = LoggerFactory.getLogger(SdkWalletBinder::class.java)
    }
}
