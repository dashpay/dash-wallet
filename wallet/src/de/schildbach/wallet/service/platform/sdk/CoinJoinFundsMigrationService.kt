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

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.Utils
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Pure helpers (host-JVM testable) ──────────────────────────────────

/**
 * The ACCOUNT-LEVEL DIP-9 CoinJoin derivation path for [params], account 0,
 * in the exact textual form the SDK's Rust side parses.
 *
 * FORMAT CONTRACT (funds-critical — see the class KDoc for how it was
 * verified): `m/9'/<coinType>'/4'/0'`, i.e. a literal `m` first component,
 * `/` separators, and an apostrophe hardened marker. `coinType` is `5` on
 * mainnet and `1` on every other network.
 */
fun coinJoinAccountPath(params: NetworkParameters): String {
    // key-wallet's AccountType::derivation_path uses `if network == Mainnet { 5 } else { 1 }`;
    // dashj's DerivationPathFactory uses the same coin types, which is why the
    // two engines agree on which keys hold the mixed coins.
    val coinType = if (NetworkParameters.ID_MAINNET == params.id) 5 else 1
    return "m/9'/$coinType'/4'/0'"
}

/**
 * Every unspent output the wallet's DIP-9 CoinJoin keychain owns, or null
 * when that cannot be determined (no CoinJoin extension, or dashj threw).
 *
 * Null means "unknown", NOT "none" — callers must fail closed on it.
 *
 * `CoinJoinExtension.getOutputs()` buckets the wallet's unspents by CoinJoin
 * denomination after a keychain-membership test; the sentinel bucket `-2`
 * holds the outputs whose key is NOT on the CoinJoin keychain, so every
 * other bucket (including `0`, the non-denominated remainder) is exactly the
 * `m/9'` account's UTXO set. Deliberately NOT `WalletEx.getCoinJoinBalance()`,
 * which counts only fully-mixed DENOMINATED coins and therefore understates
 * what the drain/shield would actually move.
 */
fun coinJoinOutputsOrNull(wallet: Wallet): List<TransactionOutput>? = try {
    val coinJoin = (wallet as? WalletEx)?.coinJoin
    coinJoin?.outputs
        ?.filterKeys { it != COIN_JOIN_FOREIGN_BUCKET }
        ?.values
        ?.flatten()
} catch (e: Exception) {
    LoggerFactory.getLogger("CoinJoinFundsMigration")
        .warn("failed to enumerate the CoinJoin keychain outputs", e)
    null
}

/** Total value on the CoinJoin keychain, or null when it cannot be read. */
fun coinJoinBalanceOrNull(wallet: Wallet): Coin? =
    coinJoinOutputsOrNull(wallet)?.fold(Coin.ZERO) { total, output -> total.add(output.value) }

/**
 * `AccountTypeTagFFI::CoinJoin` — the numeric `typeTag` the account-balance
 * JSON reports for a DIP-9 CoinJoin account. Value from rs-platform-wallet-ffi
 * `wallet_restore_types.rs` (`CoinJoin = 1`), mapped from
 * `AccountType::CoinJoin { index }` by `account_type_to_tags` (which also
 * carries the account `index` through) and written into the JSON as
 * `e.type_tag as u8` by the JNI bridge's `walletManagerAccountBalances`
 * (rs-unified-sdk-jni `dashpay.rs`) — the same snapshot
 * [pooledSpendableDuffs] and [parseBip44SpendableDuffs] read.
 *
 * NOT to be confused with the transaction BUILDER's account-type selector,
 * `CoreTransactionBuilder.AccountType.COIN_JOIN`, whose numeric value is 2 —
 * that enum (0 BIP44, 1 BIP32, 2 CoinJoin) never appears in this JSON.
 */
internal const val ACCOUNT_TYPE_TAG_COIN_JOIN = 1

/**
 * Emergency kill-switch for the mixed-funds migration prompt. Keep this false
 * in production: cutover builds otherwise count CoinJoin funds in the header
 * while leaving them unreachable from normal sends, with no in-app recovery
 * path. Checked by both the prompt gate and the dialog's own
 * [de.schildbach.wallet.ui.migration.MixedFundsMigrationDialogFragment.showOnce],
 * so setting it true suppresses every sheet entry point.
 */
const val MIXED_FUNDS_PROMPT_HARD_SUPPRESSED = false

/**
 * The SDK engine's CONFIRMED balance of the DIP-9 CoinJoin account 0 from
 * the `accountBalances` JSON snapshot, in duffs — or null when it cannot be
 * affirmatively read (null/empty/malformed input, or no CoinJoin index-0 row
 * in the snapshot). Null means "unknown", NOT "none" — the caller merges it
 * with the dashj read and only a source that affirmatively reports a
 * positive balance may trigger the prompt.
 *
 * Index 0 ONLY, deliberately: both migrations can move only that account's
 * coins (the shield's `fundingPath` is [coinJoinAccountPath] — account 0 —
 * and the combine drain targets `AccountType.COIN_JOIN` index 0), so
 * counting any other index would quote an amount the actions cannot reach.
 * `confirmed` only: the engine funds from confirmed/instant-locked outputs,
 * so unconfirmed coins could not back either migration — understating is
 * the safe direction (under-prompt, never mis-quote).
 */
internal fun parseCoinJoinConfirmedDuffs(accountBalancesJson: String?): Long? {
    if (accountBalancesJson.isNullOrEmpty()) return null
    return try {
        val rows = org.json.JSONArray(accountBalancesJson)
        var confirmed: Long? = null
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optInt("typeTag", -1) != ACCOUNT_TYPE_TAG_COIN_JOIN) continue
            if (row.optInt("index", -1) != 0) continue
            confirmed = (confirmed ?: 0L) + row.optLong("confirmed", 0L)
        }
        confirmed
    } catch (e: Exception) {
        null
    }
}

/**
 * Merge the two detector sources into the mixed-funds figure, in duffs —
 * or null when NEITHER source could be read (both unknown → nothing to
 * trust, fail closed). When at least one source answered, the result is the
 * MAX of the two (an unreadable source counts as 0): dashj is authoritative
 * pre-cutover and reads 0/unknown on a post-cutover restore (the held
 * engine never syncs), while the SDK is authoritative post-cutover and
 * unstarted (null) pre-cutover — the max is therefore always the live
 * engine's view, and a positive figure always comes from a source that
 * affirmatively reported it.
 */
internal fun mergedMixedFundsDuffs(sdkConfirmedDuffs: Long?, dashjDuffs: Long?): Long? =
    if (sdkConfirmedDuffs == null && dashjDuffs == null) {
        null
    } else {
        maxOf(sdkConfirmedDuffs ?: 0L, dashjDuffs ?: 0L)
    }

/**
 * The `getOutputs()` bucket key for outputs whose key is NOT on the CoinJoin
 * keychain (dashj `CoinJoinExtension.getOutputs`).
 */
private const val COIN_JOIN_FOREIGN_BUCKET = -2

/**
 * Is the L1 chain view current enough to TRUST a zero/non-zero CoinJoin
 * balance? A mid-sync wallet reports partial UTXO state, so prompting on it
 * would either nag a user with no mixed funds or quote the wrong amount.
 *
 * Requires TRUE completion ([BlockchainState.isSynced]: not replaying,
 * `percentageSync == 100`, no network impediment). It used to also accept a
 * chain tip younger than an hour, mirroring the shielded-transfer screen's
 * freshness gate — that was wrong HERE: post-cutover `bestChainDate` tracks
 * the FILTER-SCAN position ([deriveBlockchainStateUpdate]), not the network
 * tip, so during the from-scratch scan it walks forward through recent
 * history and crosses the one-hour bar well before the scan finishes. The
 * forced prompt then appeared over a balance that was still wrong, which is
 * the one moment it must not (Brian, approved).
 */
internal fun BlockchainState?.isSyncedEnoughForMixedFundsCheck(): Boolean =
    this != null && isSynced()

// ── The migration service ─────────────────────────────────────────────

/** Which of the two migrations was attempted. */
enum class MixedFundsMigrationAction {
    /** "Move to shielded balance". */
    SHIELD,

    /** "Keep it spendable in Dash" / "Transfer to un-mixed balance". */
    COMBINE
}

/**
 * A migration that BROADCAST ([MixedFundsMigrationOutcome.STARTED]) but whose
 * result is not user-visible yet — the funds left the CoinJoin account, and
 * until the shielded credit lands (SHIELD) or the drained coins show up in
 * the main balance (COMBINE) the user would otherwise see a near-zero balance
 * with no explanation (the lock-screen teardown bug, verified on-device).
 *
 * Persisted in [DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT] so the
 * processing presentation survives lock/activity-recreation and process
 * death; the marker — not the sheet — is what carries the state.
 *
 * @param baselineShieldedDuffs the shielded balance at broadcast time; the
 *   SHIELD completion signal is the balance exceeding it once the CoinJoin
 *   account has been drained. 0 for COMBINE.
 */
data class InFlightMixedFundsMigration(
    val action: MixedFundsMigrationAction,
    val startedAtMillis: Long,
    val baselineShieldedDuffs: Long
) {
    /**
     * The result never became visible within the honesty window — stop
     * showing a spinner and show the "could not confirm" guidance instead.
     */
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - startedAtMillis > MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS
}

/**
 * How long an in-flight migration may show its processing presentation
 * before the UI must stop spinning and be honest about not knowing.
 */
const val MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS = 30L * 60L * 1000L

/** `ACTION|startedAtMillis|baselineShieldedDuffs` — the persisted form. */
internal fun formatInFlightMigration(marker: InFlightMixedFundsMigration): String =
    "${marker.action.name}|${marker.startedAtMillis}|${marker.baselineShieldedDuffs}"

/**
 * Parse the persisted marker, or null when absent/blank (cleared) or
 * malformed — an unreadable marker must never wedge the UI in a permanent
 * processing state, so it reads as "no migration in flight".
 */
internal fun parseInFlightMigration(raw: String?): InFlightMixedFundsMigration? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split('|')
    if (parts.size != 3) return null
    val action = MixedFundsMigrationAction.entries.firstOrNull { it.name == parts[0] } ?: return null
    val startedAt = parts[1].toLongOrNull() ?: return null
    val baseline = parts[2].toLongOrNull() ?: return null
    return InFlightMixedFundsMigration(action, startedAt, baseline)
}

/** What the user chose, and how it went. */
enum class MixedFundsMigrationOutcome {
    /** Broadcast/submitted; the CoinJoin account is being emptied. */
    STARTED,

    /** Nothing was spent — safe to re-offer immediately. */
    NOT_ATTEMPTED,

    /** The outcome is unknown; the transaction MAY be on the network. */
    UNCONFIRMED
}

/**
 * Post-upgrade MIXED-FUNDS migration.
 *
 * CoinJoin mixing was removed from the app, but wallets that mixed in the
 * past still hold coins on the DIP-9 CoinJoin keychain (`m/9'/coin'/4'/0'`).
 * Those coins are invisible to the SDK's default BIP44-only spend paths — the
 * live symptom in `docs/kotlin-sdk-issues-to-file.md:113` (a 1.534 DASH wallet
 * reporting 0.09 DASH available). This service detects them and performs
 * whichever of the two one-time migrations the user picks:
 *
 * - [shieldMixedFunds] — asset-lock them into the SHIELDED (Orchard) balance,
 *   funded strictly from the CoinJoin account via the SDK's `fundingPath`.
 * - [combineIntoUnmixedBalance] — drain them to a fresh BIP44 receive address
 *   of the same wallet, keeping them spendable on L1 but DE-MIXING them.
 *
 * ## Privacy invariant
 *
 * Both operations are SINGLE-SOURCE-ACCOUNT transactions. Neither ever unions
 * CoinJoin UTXOs with BIP44 UTXOs — the design that was rejected in review for
 * "silently crossing privacy domains". Consolidation publicly links its inputs,
 * including when its destination stays in the CoinJoin account. The shielded
 * destination protects subsequent activity; the choice text explains the linkage.
 *
 * ## The `fundingPath` format, and how it was verified
 *
 * `m/9'/5'/4'/0'` (mainnet) / `m/9'/1'/4'/0'` (everything else). Verified
 * against the pinned SDK sources rather than assumed:
 *
 * 1. `rs-platform-wallet-ffi/src/shielded_send.rs` `parse_optional_derivation_path`
 *    parses the UTF-8 bytes with `key_wallet::bip32::DerivationPath::from_str`.
 * 2. That `FromStr` (`key-wallet/src/bip32.rs:1297`) REQUIRES the first
 *    `/`-separated component to be literally `"m"` and otherwise errors, and
 *    `ChildNumber::from_str` (`:887`) treats a trailing `'` or `h` as hardened.
 * 3. `rs-platform-wallet/src/wallet/asset_lock/build.rs:355-382` compares the
 *    parsed path for EQUALITY against each funds account's
 *    `managed_account_type().to_account_type().derivation_path(network)`.
 * 4. `key-wallet/src/account/account_type.rs:344-357` builds exactly
 *    `[9', coin_type', 4', index']` for `AccountType::CoinJoin { index }`,
 *    with `coin_type = 5` on mainnet and `1` otherwise (`:314-318`).
 *
 * The CoinJoin account itself exists on every SDK wallet the app creates:
 * `DashSdkServiceImpl` passes `createDefaultAccounts = true`, which reaches
 * key-wallet's `helper.rs` `add_account(AccountType::CoinJoin { index: 0 })`.
 */
@Singleton
class CoinJoinFundsMigrationService @Inject constructor(
    private val walletData: WalletData,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val sdkL1SendService: SdkL1SendService,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val dashPayConfig: DashPayConfig,
    private val dashSdkService: DashSdkService,
    private val applicationScope: CoroutineScope
) {
    /** The in-flight completion watcher — see [startInFlightWatcherIfNeeded]. */
    private var inFlightWatcherJob: Job? = null
    private val migrationMutex = Mutex()
    /**
     * The mixed-funds balance to offer to migrate, or null when there is
     * nothing to do — no readable source, a zero balance, or an L1 view too
     * stale to trust.
     *
     * TWO detector sources, merged by [mergedMixedFundsDuffs] (max):
     * - the SDK engine's CoinJoin account 0 confirmed balance
     *   ([sdkCoinJoinConfirmedDuffs]) — the AUTHORITATIVE view post-cutover,
     *   where the held dashj wallet never syncs and therefore reads an empty
     *   `m/9'` UTXO set on any freshly restored wallet (the live bug this
     *   dual-source detector fixes: restore-with-mixed-funds never prompted);
     * - the held dashj wallet's CoinJoin keychain ([coinJoinBalanceOrNull]) —
     *   correct pre-cutover, harmless 0/unknown after it.
     *
     * Never throws: any failure reads as "nothing to migrate", so a broken
     * probe can only under-prompt, never mis-prompt.
     */
    suspend fun mixedFundsToMigrate(): Coin? = withContext(Dispatchers.IO) {
        try {
            val state = blockchainStateProvider.getState()
            if (!state.isSyncedEnoughForMixedFundsCheck()) {
                log.debug("mixed-funds probe skipped: L1 view not current enough to trust")
                return@withContext null
            }
            val sdkDuffs = sdkCoinJoinConfirmedDuffs()
            @Suppress("DEPRECATION")
            val dashjDuffs = walletData.wallet?.let { coinJoinBalanceOrNull(it) }?.value
            val duffs = mergedMixedFundsDuffs(sdkDuffs, dashjDuffs) ?: return@withContext null
            if (duffs > 0L) {
                log.info(
                    "mixed funds detected by {}: sdk={} duffs, dashj={} duffs",
                    if ((sdkDuffs ?: 0L) >= duffs) "the SDK engine" else "the dashj wallet",
                    sdkDuffs,
                    dashjDuffs
                )
            }
            Coin.valueOf(duffs).takeIf { it.isPositive }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("mixed-funds probe failed; treating the wallet as having none", t)
            null
        }
    }

    /**
     * The SDK engine's confirmed CoinJoin-account-0 balance in duffs, or
     * null when it cannot be affirmatively read (SDK not started with no
     * committed cutover, no bound wallet, or a read/parse failure) —
     * [parseCoinJoinConfirmedDuffs] over the same
     * `PlatformWalletManager.accountBalances` snapshot the DashPay
     * receival-fallback reads.
     *
     * Start policy: PASSIVE pre-cutover (a null manager reads as unknown —
     * dashj is the authority there and booting the SDK just for a probe
     * would change startup behavior), but once the cutover is COMMITTED the
     * SDK is the L1 owner and boots at startup anyway, so [ensureStarted]
     * is the correct way to reach the only balance source dashj can no
     * longer provide.
     */
    private suspend fun sdkCoinJoinConfirmedDuffs(): Long? = try {
        var manager = dashSdkService.walletManagerOrNull()
        if (manager == null && sdkL1SendService.cutoverCommitted()) {
            dashSdkService.ensureStarted()
            manager = dashSdkService.walletManagerOrNull()
        }
        val walletIdHex = manager?.wallets?.value?.keys?.singleOrNull()
        if (walletIdHex == null) {
            null
        } else {
            // The manager keys wallets by its own hex encoding; HEX.decode
            // requires lowercase, so normalize defensively.
            parseCoinJoinConfirmedDuffs(
                manager.accountBalances(Utils.HEX.decode(walletIdHex.lowercase()))
            )
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("SDK CoinJoin balance probe failed; treating the SDK view as unknown", t)
        null
    }

    /**
     * Should the one-time prompt be shown right now? True only when the user
     * has not already completed a migration AND the wallet still holds mixed
     * funds. A FAILED attempt deliberately leaves the completion flag unset,
     * so the prompt returns on the next startup (retry-on-failure); a
     * SUCCESSFUL one empties the account, so this goes false on its own even
     * before the flag lands.
     */
    suspend fun shouldPrompt(): Boolean {
        // Emergency brake only. The normal gating below must run in cutover
        // builds so wallets whose header balance includes CoinJoin-account
        // funds have an in-app way to make those coins spendable again.
        if (MIXED_FUNDS_PROMPT_HARD_SUPPRESSED) return false
        if (restorePendingShieldMigration()) return true
        val alreadyHandled = try {
            dashPayConfig.get(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) == true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Fail CLOSED on a read error: never nag when we cannot tell.
            log.warn("failed to read the mixed-funds migration flag; not prompting", t)
            true
        }
        if (alreadyHandled) return false
        // DIAGNOSTIC suppression (Brian, 2026-07-31): while the dashj-sync
        // diagnostic toggle is ON, the forced mixed-funds prompt must NOT
        // appear — a support/parity session must keep Tools, the verdict
        // readout, and the send-logs flow reachable, and must never push the
        // tester to move funds mid-diagnostic. The prompt now genuinely does
        // wait for the SDK sync to complete ([isSyncedEnoughForMixedFundsCheck]
        // requires BlockchainState.isSynced()) — the earlier version of this
        // note claimed that while the gate still accepted a merely FRESH chain
        // tip, which post-cutover fired mid-scan — so a tester has the whole
        // sync window to enable the toggle; once it is turned OFF the next
        // prompt evaluation (state emission / resume) shows the sheet as usual.
        // Fail OPEN on a read error (flag unreadable → normal prompting).
        val diagnosticActive = try {
            dashPayConfig.getDashjSyncDiagnostic()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            false
        }
        if (diagnosticActive) {
            log.info("mixed-funds prompt deferred: dashj-sync diagnostic is active")
            return false
        }
        if (!anyOptionOperable()) {
            // Do NOT prompt when neither option could run: a failed attempt
            // deliberately leaves the flag unset, so offering a choice that
            // is guaranteed to fail would re-nag on every single launch.
            log.debug("mixed-funds prompt suppressed: neither migration path is operable yet")
            return false
        }
        val amount = mixedFundsToMigrate() ?: return false
        val deferred = dashPayConfig.get(DashPayConfig.MIXED_FUNDS_MIGRATION_DEFERRED_DUFFS)
        return deferred == null || amount.value > deferred
    }

    suspend fun deferMigration(amount: Coin) {
        dashPayConfig.set(DashPayConfig.MIXED_FUNDS_MIGRATION_DEFERRED_DUFFS, amount.value)
    }

    /** Continue the persisted consolidation stage, or resume the existing asset lock. */
    suspend fun retryPendingMigration(): MixedFundsMigrationOutcome {
        if (!restorePendingShieldMigration()) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        val marker = inFlightMigration() ?: return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        val hasConsolidation = !dashPayConfig.get(DashPayConfig.MIXED_FUNDS_CONSOLIDATION).isNullOrBlank()
        if (marker.action != MixedFundsMigrationAction.SHIELD && !hasConsolidation) {
            return MixedFundsMigrationOutcome.UNCONFIRMED
        }
        // A user-requested retry gets a fresh observation window, retaining
        // the original balance baseline and the SDK's existing lock.
        dashPayConfig.set(
            DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT,
            formatInFlightMigration(marker.copy(startedAtMillis = System.currentTimeMillis()))
        )
        inFlightWatcherJob?.cancelAndJoin()
        inFlightWatcherJob = null
        startInFlightWatcherIfNeeded()
        if (hasConsolidation) {
            return MixedFundsMigrationOutcome.STARTED
        }
        return if (shieldedBalanceService.resumePendingWalletShields() > 0) {
            startInFlightWatcherIfNeeded()
            MixedFundsMigrationOutcome.STARTED
        } else {
            MixedFundsMigrationOutcome.UNCONFIRMED
        }
    }

    /**
     * Is at least ONE of the two migrations currently runnable? Both spend
     * through the SDK engine (dashj has no CoinJoin→shielded path and cannot
     * point the SDK builder at an account), so both are inert while the
     * engine is not the L1 owner and the shielded funding gate is shut.
     * Conservative: [SdkL1SendService.cutoverCommitted] is a subset of the
     * drain's own enablement rule, so this can only under-prompt.
     */
    private suspend fun anyOptionOperable(): Boolean = try {
        shieldedBalanceService.isWalletShieldingAvailable() || sdkL1SendService.cutoverCommitted()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to probe the migration paths; not prompting", t)
        false
    }

    /**
     * OPTION A — shield the mixed funds.
     *
     * Consolidates large CoinJoin accounts within their own address pool before
     * draining into the wallet's Orchard address. [amount] is the UI estimate;
     * the SDK computes the actual lock value after fees.
     */
    suspend fun shieldMixedFunds(amount: Dash): MixedFundsMigrationOutcome = migrationMutex.withLock {
        if (restorePendingShieldMigration()) return MixedFundsMigrationOutcome.STARTED
        if (!blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck()) {
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        val snapshot = coinJoinConsolidationSnapshot(dashSdkService)
            ?: return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (snapshot.coins.isEmpty()) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (snapshot.needsConsolidation) {
            val destination = snapshot.destination ?: return MixedFundsMigrationOutcome.NOT_ATTEMPTED
            if (snapshot.batchAmount() <= 0L) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
            saveConsolidation(CoinJoinConsolidationState(snapshot.walletId, destination))
            markInFlight(MixedFundsMigrationAction.SHIELD)
            return MixedFundsMigrationOutcome.STARTED
        }
        // Save the recovery presentation before the native call, which may
        // wait for finality indefinitely or outlive this process.
        markInFlight(MixedFundsMigrationAction.SHIELD, startWatcher = false)
        val path = coinJoinAccountPath(walletData.networkParameters)
        log.info("mixed-funds migration: shielding {} from {}", amount, path)
        val result = shieldedBalanceService.shieldMixedFundsFromWallet(amount, path)
        val outcome = result.toOutcome()
        if (outcome == MixedFundsMigrationOutcome.NOT_ATTEMPTED) clearInFlightMigration()
        return finish(MixedFundsMigrationAction.SHIELD, outcome, markerAlreadyRecorded = true)
    }

    /**
     * OPTION B — combine the mixed funds into the unmixed (BIP44) balance.
     *
     * Consolidates oversized accounts in 500-input batches, then drains to a
     * fresh BIP44 address saved for this migration. Keeps the funds spendable on L1 and DE-MIXES them:
     * the transaction publicly links the previously mixed coins to the
     * destination address.
     */
    suspend fun combineIntoUnmixedBalance(): MixedFundsMigrationOutcome = migrationMutex.withLock {
        if (restorePendingShieldMigration()) return MixedFundsMigrationOutcome.STARTED
        if (!blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck()) {
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        val snapshot = coinJoinConsolidationSnapshot(dashSdkService)
            ?: return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (snapshot.coins.isEmpty() || snapshot.batchAmount() <= 0L) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (!snapshot.drain && snapshot.destination == null) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        val destination = try {
            // A FRESH address, not the current one: reusing an address the
            // user has already published would link the mixed coins to it.
            //
            // OFF THE MAIN THREAD, deliberately: dashj's freshReceiveAddress()
            // issues a key and then forces a SYNCHRONOUS full-wallet save, and
            // that save re-serializes every DashPay friend key chain. Measured
            // on a 215-chain wallet that is a 1.3 s main-thread block, and on a
            // slower device with a larger wallet ~7 s — long enough that a
            // tester read it as a hang and force-quit mid-migration. The caller
            // runs on Main, so the dispatcher has to be imposed here.
            withContext(Dispatchers.IO) { walletData.freshReceiveAddressString() }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("mixed-funds migration: could not derive an own receive address", t)
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        saveConsolidation(CoinJoinConsolidationState(
            snapshot.walletId, snapshot.destination ?: destination, regularDestination = destination
        ))
        markInFlight(MixedFundsMigrationAction.COMBINE)
        return MixedFundsMigrationOutcome.STARTED
    }

    /** Record the migration as handled unless it provably did nothing. */
    private suspend fun finish(
        action: MixedFundsMigrationAction,
        outcome: MixedFundsMigrationOutcome,
        markerAlreadyRecorded: Boolean = false
    ): MixedFundsMigrationOutcome {
        if (outcome == MixedFundsMigrationOutcome.NOT_ATTEMPTED) {
            // Nothing was spent — leave the flag unset so the prompt returns
            // on the next startup. This is the retry path.
            log.warn("mixed-funds migration ({}) did not run; the prompt will reappear", action)
            return outcome
        }
        if (outcome == MixedFundsMigrationOutcome.STARTED) {
            // The broadcast succeeded but the RESULT is not visible yet: the
            // funds left the CoinJoin account and, until the shielded credit
            // (SHIELD) or the drained coins (COMBINE) land, the user's
            // balance reads near-zero with nothing to explain it. Persist
            // the in-flight marker so the processing presentation survives
            // lock/recreation/process death; [startInFlightWatcherIfNeeded]
            // clears it once the result is user-visible. Best-effort: a
            // marker failure must not disturb the migration outcome (worst
            // case is the old behavior — no resumed processing sheet).
            try {
                if (!markerAlreadyRecorded && inFlightMigration() == null) markInFlight(action)
                else startInFlightWatcherIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to persist the in-flight mixed-funds marker", t)
            }
        }
        try {
            dashPayConfig.set(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE, true)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Harmless: the account is (being) emptied, so [shouldPrompt]'s
            // balance check already suppresses the prompt.
            log.warn("failed to persist the mixed-funds migration flag", t)
        }
        return outcome
    }

    // ── The in-flight (post-broadcast) window ─────────────────────────

    /** Recover older interrupted shields that predate the early marker write. */
    suspend fun restorePendingShieldMigration(): Boolean {
        if (inFlightMigration() != null) {
            startInFlightWatcherIfNeeded()
            return true
        }
        val consolidation = dashPayConfig.get(DashPayConfig.MIXED_FUNDS_CONSOLIDATION)
        if (!consolidation.isNullOrBlank()) {
            markInFlight(CoinJoinConsolidationState.decode(consolidation).action)
            return true
        }
        if (!shieldedBalanceService.ensureShieldedReady()) return false
        if ((shieldedBalanceService.pendingCoinJoinShieldLockCount() ?: 0) <= 0) return false
        // A pending CoinJoin-funded shield is enough to block either migration:
        // its reserved inputs may still appear in the confirmed account
        // balance. Unrelated BIP44 shield locks are not migration progress.
        markInFlight(MixedFundsMigrationAction.SHIELD)
        log.info("mixed-funds migration: restored progress for a pending wallet shield")
        return true
    }

    /**
     * The persisted in-flight migration, or null when none is pending —
     * never set, already completed (result visible), or cleared after its
     * timed-out state was acknowledged. Never throws.
     */
    suspend fun inFlightMigration(): InFlightMixedFundsMigration? = try {
        parseInFlightMigration(dashPayConfig.get(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT))
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to read the in-flight mixed-funds marker", t)
        null
    }

    /** Reactive mirror of [inFlightMigration] — emits null once the result lands. */
    fun observeInFlightMigration(): Flow<InFlightMixedFundsMigration?> =
        dashPayConfig.observe(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT)
            .map { parseInFlightMigration(it) }

    /**
     * Drop the marker. Called by the watcher when the result became visible,
     * and by the UI when the user acknowledges (dismisses) the TIMED-OUT
     * presentation — a marker past [MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS] is
     * deliberately NOT auto-cleared, so the "could not confirm" guidance is
     * shown at least once before it stops re-appearing.
     */
    suspend fun clearInFlightMigration() {
        try {
            dashPayConfig.set(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, "")
            dashPayConfig.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, "")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to clear the in-flight mixed-funds marker", t)
        }
    }

    private suspend fun markInFlight(action: MixedFundsMigrationAction, startWatcher: Boolean = true) {
        val baseline = if (action == MixedFundsMigrationAction.SHIELD) {
            // The shielded balance the credit must EXCEED to count as
            // landed. Read live (the note store has not credited the shield
            // yet — that takes a sync pass), falling back to the persisted
            // last-READY figure, then 0 (a 0 baseline can only clear the
            // marker EARLY when a credit shows up, never keep it stuck).
            withTimeoutOrNull(BASELINE_READ_TIMEOUT_MS) {
                shieldedBalanceService.observeShieldedBalance().first()
            }?.duffs
                ?: dashPayConfig.getLastShieldedBalanceDuffs()
                ?: 0L
        } else {
            0L
        }
        val marker = InFlightMixedFundsMigration(action, System.currentTimeMillis(), baseline)
        dashPayConfig.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, formatInFlightMigration(marker))
        log.info("mixed-funds migration in flight: {} (baseline {} duffs)", action, baseline)
        if (startWatcher) startInFlightWatcherIfNeeded()
    }

    /**
     * Start (idempotently) the watcher that clears the in-flight marker once
     * the migration's RESULT is user-visible:
     *
     * - SHIELD — the CoinJoin funds moved AND the shielded credit lands: the
     *   CoinJoin account reads affirmatively EMPTY (as for COMBINE), no
     *   CoinJoin-funded lock is still pending
     *   ([ShieldedBalanceService.pendingCoinJoinShieldLockCount] == 0), and
     *   [ShieldedBalanceService.observeShieldedBalance] emits a balance above
     *   the marker's baseline (the flow emits `Dash.ZERO`/the pre-credit
     *   figure until then, so a runtime rebind can never fake a completion).
     *   The balance alone is not enough — an unrelated BIP44 shield landing
     *   would raise it while the CoinJoin coins stay put;
     * - COMBINE — the CoinJoin account reads EMPTY in the SDK's own
     *   `accountBalances` snapshot ([parseCoinJoinConfirmedDuffs] == 0,
     *   affirmatively): the drain consumed the account and its coins are now
     *   on a BIP44 address of this same wallet, i.e. inside the main
     *   balance every surface reads. Polled — the snapshot has no push feed.
     *
     * The watch is bounded by the marker's remaining honesty window
     * ([MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS] from broadcast): past it the
     * watcher stands down WITHOUT clearing, and the UI shows the
     * "could not confirm" guidance instead of spinning forever.
     *
     * Called after a marker is written and from [de.schildbach.wallet.ui
     * .main.MainViewModel]'s init, so a relaunch mid-window re-arms it.
     * Cheap when nothing is in flight (one DataStore read, then exit).
     */
    fun startInFlightWatcherIfNeeded() {
        synchronized(this) {
            if (inFlightWatcherJob?.isActive == true) return
            inFlightWatcherJob = applicationScope.launch {
                try {
                    watchInFlightMigration()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.warn("the in-flight mixed-funds watcher stopped", t)
                    expireMigration()
                }
            }
        }
    }

    private suspend fun watchInFlightMigration() {
        val marker = inFlightMigration() ?: return
        val remainingMs = marker.startedAtMillis + MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            log.info("in-flight mixed-funds migration ({}) already past the honesty window", marker.action)
            return
        }
        val landed = withTimeoutOrNull(remainingMs) {
            if (!continueConsolidation()) return@withTimeoutOrNull false
            when (marker.action) {
                MixedFundsMigrationAction.SHIELD -> {
                    // The CoinJoin funds must have actually moved: the account
                    // reads empty and no CoinJoin-funded lock is still pending.
                    // A rising shielded balance alone could be an unrelated
                    // BIP44 shield landing.
                    while (sdkCoinJoinConfirmedDuffs() != 0L ||
                        !shieldedBalanceService.ensureShieldedReady() ||
                        shieldedBalanceService.pendingCoinJoinShieldLockCount() != 0
                    ) {
                        delay(IN_FLIGHT_POLL_MS)
                    }
                    shieldedBalanceService.observeShieldedBalance()
                        .first { it.duffs > marker.baselineShieldedDuffs }
                }
                MixedFundsMigrationAction.COMBINE ->
                    while (sdkCoinJoinConfirmedDuffs() != 0L) {
                        delay(IN_FLIGHT_POLL_MS)
                    }
            }
            true
        } ?: false
        if (landed) {
            log.info("mixed-funds migration ({}) result is now visible; clearing the in-flight marker", marker.action)
            clearInFlightMigration()
        } else {
            log.warn(
                "mixed-funds migration ({}) result did not become visible within {} min",
                marker.action,
                MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS / 60_000L
            )
        }
    }

    private suspend fun saveConsolidation(state: CoinJoinConsolidationState) {
        dashPayConfig.set(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, state.encode())
    }

    /** A persisted txid is written before broadcast; an uncertain send is only observed, never rebuilt. */
    private suspend fun continueConsolidation(): Boolean {
        val raw = dashPayConfig.get(DashPayConfig.MIXED_FUNDS_CONSOLIDATION)
        if (raw.isNullOrBlank()) return true // Legacy/direct asset-lock recovery.
        var state = CoinJoinConsolidationState.decode(raw)
        while (true) {
            dashSdkService.ensureStarted()
            val activeWallet = dashSdkService.walletManagerOrNull()?.wallets?.value?.keys?.singleOrNull()
            if (activeWallet != state.walletId) return false
            if (state.phase == CoinJoinConsolidationState.SHIELDING) {
                if (!shieldedBalanceService.ensureShieldedReady()) {
                    delay(IN_FLIGHT_POLL_MS)
                    continue
                }
                val pending = shieldedBalanceService.pendingCoinJoinShieldLockCount()
                if (pending == null) { delay(IN_FLIGHT_POLL_MS); continue }
                if (pending > 0) {
                    shieldedBalanceService.resumePendingWalletShields()
                    return true
                }
                // No CoinJoin-funded lock: either the drain landed (account
                // empty or its coins already claimed) or it never started —
                // interrupted before the native call, or an earlier build
                // mistook an unrelated BIP44 lock for it. Drain again only
                // from a clean, settled snapshot.
                val snapshot = coinJoinConsolidationSnapshot(dashSdkService)
                if (snapshot == null || snapshot.coins.isEmpty()) return true
                state = state.copy(phase = CoinJoinConsolidationState.READY)
                saveConsolidation(state)
                continue
            }
            if (!blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck()) {
                delay(IN_FLIGHT_POLL_MS)
                continue
            }
            if (state.phase == CoinJoinConsolidationState.WAITING || state.phase == CoinJoinConsolidationState.TRANSFERRING) {
                val tx = dashSdkService.databaseOrNull()?.transactionDao()
                    ?.getByTxid(Utils.HEX.decode(state.txid).reversedArray())
                if (tx == null || tx.context !in 1..3) {
                    delay(IN_FLIGHT_POLL_MS)
                    continue
                }
            }
            val snapshot = coinJoinConsolidationSnapshot(dashSdkService)
            if (state.phase == CoinJoinConsolidationState.TRANSFERRING) {
                if (snapshot != null && snapshot.coins.isEmpty()) return true
                // The final transaction is settled; wait for the engine and
                // persistence to agree that its CoinJoin inputs are spent.
                delay(IN_FLIGHT_POLL_MS)
                continue
            }
            if (snapshot == null || snapshot.coins.isEmpty()) {
                delay(IN_FLIGHT_POLL_MS)
                continue
            }
            if (state.phase == CoinJoinConsolidationState.WAITING) {
                // Finality and the updated UTXO set must both have reached Room.
                val txid = Utils.HEX.decode(state.txid).reversedArray()
                if (snapshot.coins.none { it.txid?.contentEquals(txid) == true && it.address == state.destination }) {
                    delay(IN_FLIGHT_POLL_MS)
                    continue
                }
                state = state.copy(phase = CoinJoinConsolidationState.READY, txid = "")
                saveConsolidation(state)
            }
            if (state.action == MixedFundsMigrationAction.SHIELD && !snapshot.needsConsolidation) {
                if (!shieldedBalanceService.ensureShieldedReady()) {
                    delay(IN_FLIGHT_POLL_MS)
                    continue
                }
                // Only a CoinJoin-funded lock is this migration's pending work;
                // an unrelated BIP44 shield lock is left to the regular sweep
                // and must not stand in for the drain.
                val pending = shieldedBalanceService.pendingCoinJoinShieldLockCount()
                if (pending == null) { delay(IN_FLIGHT_POLL_MS); continue }
                state = state.copy(phase = CoinJoinConsolidationState.SHIELDING)
                saveConsolidation(state)
                if (pending > 0) {
                    shieldedBalanceService.resumePendingWalletShields()
                    return true
                }
                val result = shieldedBalanceService.shieldMixedFundsFromWallet(
                    Dash.valueOf(snapshot.coins.sumOf { it.amount }), coinJoinAccountPath(walletData.networkParameters)
                )
                if (result is SdkWriteResult.NotBroadcast) {
                    saveConsolidation(state.copy(phase = CoinJoinConsolidationState.READY))
                    expireMigration()
                    return false
                }
                return true
            }
            val amount = snapshot.batchAmount()
            if (amount <= 0L) { expireMigration(); return false }
            val finalTransfer = state.action == MixedFundsMigrationAction.COMBINE && snapshot.drain
            val destination = if (finalTransfer) state.regularDestination else state.destination
            val result = sdkL1SendService.consolidateCoinJoin(destination, amount, snapshot.drain) { bytes ->
                val txid = validateCoinJoinConsolidation(bytes, snapshot, destination, walletData.networkParameters,
                    minimumInputs = if (finalTransfer) 1 else 2)
                state = state.copy(phase = if (finalTransfer) CoinJoinConsolidationState.TRANSFERRING
                    else CoinJoinConsolidationState.WAITING, txid = txid)
                saveConsolidation(state)
                log.info("CoinJoin consolidation prepared: {} ({} available inputs)", txid, snapshot.coins.size)
            }
            if (result is SdkWriteResult.NotBroadcast) {
                state = state.copy(phase = CoinJoinConsolidationState.READY, txid = "")
                saveConsolidation(state)
                expireMigration()
                return false
            }
            // Ambiguous without a prepared txid cannot safely start another build.
            if (state.phase !in setOf(CoinJoinConsolidationState.WAITING, CoinJoinConsolidationState.TRANSFERRING)) {
                expireMigration(); return false
            }
            delay(IN_FLIGHT_POLL_MS)
        }
    }

    private suspend fun expireMigration() {
        val marker = inFlightMigration() ?: return
        dashPayConfig.set(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT,
            formatInFlightMigration(marker.copy(startedAtMillis = System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 1)))
    }

    private fun SdkWriteResult<*>.toOutcome(): MixedFundsMigrationOutcome = when (this) {
        is SdkWriteResult.Broadcast -> MixedFundsMigrationOutcome.STARTED
        is SdkWriteResult.NotBroadcast -> MixedFundsMigrationOutcome.NOT_ATTEMPTED
        is SdkWriteResult.Ambiguous -> MixedFundsMigrationOutcome.UNCONFIRMED
    }

    companion object {
        private val log = LoggerFactory.getLogger(CoinJoinFundsMigrationService::class.java)

        /**
         * Headroom left unlocked so the L1 asset-lock fee has somewhere to
         * come from. Same figure as the send-all reserve
         * ([SEND_ALL_FEE_RESERVE_DUFFS]); whatever the fee does not consume
         * comes back as BIP44 change, so nothing is stranded on `m/9'`.
         */
        const val L1_FEE_RESERVE_DUFFS = SEND_ALL_FEE_RESERVE_DUFFS

        /**
         * How long the marker write may wait for a live shielded-balance
         * read before falling back to the persisted last-READY figure.
         */
        private const val BASELINE_READ_TIMEOUT_MS = 5_000L

        /**
         * COMBINE completion poll cadence — the `accountBalances` snapshot
         * has no push feed, so the watcher re-reads it on this interval.
         */
        private const val IN_FLIGHT_POLL_MS = 15_000L
    }
}
