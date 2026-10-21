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

import androidx.datastore.preferences.core.Preferences
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.dao.InstantSendLockDao
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.Utils
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.entities.AssetLockEntity
import org.dashfoundation.dashsdk.persistence.entities.TxoEntity
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
 * Does [wallet] hold an app-locked output (e.g. CrowdNode, via dashj
 * `Wallet.lockOutput`) on the CoinJoin keychain? True when that cannot be told:
 * the CoinJoin drains spend the whole account and cannot exclude an input.
 */
fun coinJoinOutputsAppLocked(wallet: Wallet?): Boolean = try {
    wallet == null || coinJoinOutputsOrNull(wallet)?.any { wallet.isLockedOutput(it.outPointFor) } ?: true
} catch (e: Exception) {
    LoggerFactory.getLogger("CoinJoinFundsMigration").warn("CoinJoin locked-output check failed; treating as locked", e)
    true
}

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
 * affirmatively read (null/empty/malformed input — including a row whose
 * tag, index or `confirmed` field is missing or non-numeric — or no CoinJoin
 * index-0 row in the snapshot). Null means "unknown", NOT "none" — see
 * [mixedFundsDuffs]: post-cutover it is the only source (null → no prompt);
 * pre-cutover it is merged with the dashj read.
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
            // A missing/non-numeric field is UNKNOWN (null), never a skipped row or 0.
            if ((row.balanceRowIntOrNull("typeTag") ?: return null) != ACCOUNT_TYPE_TAG_COIN_JOIN) continue
            if ((row.balanceRowIntOrNull("index") ?: return null) != 0) continue
            val value = row.balanceRowDuffsOrNull("confirmed") ?: return null
            confirmed = Math.addExact(confirmed ?: 0L, value)
        }
        confirmed
    } catch (e: Exception) {
        null
    }
}

/**
 * The mixed-funds figure to offer, in duffs, from the two detector sources —
 * or null when nothing trustworthy could be read (fail closed: no prompt).
 *
 * POST-CUTOVER ([cutoverCommitted] = true) the SDK engine is the ONLY
 * authority and [dashjDuffs] is ignored outright. The held dashj engine is
 * frozen at its pre-cutover snapshot and never syncs again, so its CoinJoin
 * balance is stale the moment those coins move: an UPGRADED wallet keeps
 * reporting its pre-cutover mixed balance forever, even after the coins were
 * shielded/spent from another device or by the SDK itself (field bug: SDK
 * coinjoin=0 but dashj 57 DASH → a "Move your mixed coins" sheet that could
 * never be acted on). A RESTORED wallet's held dashj reads 0 instead — also
 * not information. An unreadable SDK balance (null) therefore yields null,
 * never a dashj fallback.
 *
 * PRE-CUTOVER the historical merge is kept: when at least one source
 * answered, the MAX of the two (an unreadable source counts as 0); dashj is
 * the live L1 engine there and the SDK is passive (usually unstarted/null),
 * so the max is the live engine's view and a positive figure always comes
 * from a source that affirmatively reported it. Null when NEITHER answered.
 */
internal fun mixedFundsDuffs(cutoverCommitted: Boolean, sdkConfirmedDuffs: Long?, dashjDuffs: Long?): Long? =
    when {
        cutoverCommitted -> sdkConfirmedDuffs
        sdkConfirmedDuffs == null && dashjDuffs == null -> null
        else -> maxOf(sdkConfirmedDuffs ?: 0L, dashjDuffs ?: 0L)
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

/** The SDK's signer could not read the seed because the device is locked. */
private const val SIGNER_LOCKED_MESSAGE = "mnemonic resolver failed with code 3"

/**
 * Was this pre-broadcast failure the Keystore refusing the wallet seed while
 * the device is locked? That is temporary — the same step succeeds once the
 * user unlocks — unlike every other [SdkWriteResult.NotBroadcast], which ends
 * the attempt. Observed live (MO-1099): the drain into a shield asset lock
 * ran minutes after the user's tap, by which time the phone had locked.
 */
internal fun isSignerLockedFailure(result: SdkWriteResult.NotBroadcast): Boolean {
    val cause = result.cause ?: return false
    if (isKeystoreDenial(cause)) return true
    var t: Throwable? = cause
    var depth = 0
    while (t != null && depth++ < 8) {
        if (t.message?.contains(SIGNER_LOCKED_MESSAGE) == true) return true
        t = t.cause
    }
    return false
}

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
 * Completion never depends on a shielded balance (see [CoinJoinFundsMigrationService.startInFlightWatcherIfNeeded]),
 * so the marker carries no baseline: its persisted third field is legacy, read and ignored
 * ([parseInFlightMigration]) and always written as [LEGACY_SHIELD_BASELINE_FIELD].
 *
 * @param failed a later stage of a consolidated migration failed
 *   DEFINITIVELY (nothing submitted; every consolidation transaction settled,
 *   no asset lock uncertain). Not progress: the watcher stands down, the UI
 *   presents the failure with its fallback, and the user's next
 *   (authenticated) move drops the record before spending afresh.
 */
data class InFlightMixedFundsMigration(
    val action: MixedFundsMigrationAction,
    val startedAtMillis: Long,
    val failed: Boolean = false
) {
    /**
     * The result never became visible within the honesty window — stop
     * showing a spinner and show the "could not confirm" guidance instead.
     */
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean = remainingMillis(nowMillis) < 0L

    /**
     * What is left of the honesty window, negative once expired. Overflow-safe (compares
     * elapsed time). A start up to [MIXED_FUNDS_START_SKEW_MS] ahead (clock jitter) counts
     * as starting now; further ahead (clock moved back, corruption) it reads as expired.
     */
    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        // Both are non-negative (parse rejects negatives), so the subtraction cannot overflow.
        val elapsed = nowMillis - startedAtMillis
        if (elapsed < -MIXED_FUNDS_START_SKEW_MS) return -1L
        return MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS -
            elapsed.coerceIn(0L, MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS + 1L)
    }
}

/**
 * How long an in-flight migration may show its processing presentation
 * before the UI must stop spinning and be honest about not knowing.
 */
const val MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS = 30L * 60L * 1000L

/** How far ahead a marker's start may be and still count as starting now (clock jitter). */
internal const val MIXED_FUNDS_START_SKEW_MS = 60_000L

/**
 * The value written in the marker's legacy third (`baselineShieldedDuffs`) field. This build
 * ignores it; older builds still read it and complete a SHIELD only once the shielded balance
 * EXCEEDS it. No balance can exceed [Long.MAX_VALUE], so an older build reading a marker this
 * build wrote can never complete it on a balance (it expires into "could not confirm" instead),
 * and every older parser accepts it (unlike -1, which builds before the non-negative check
 * would have read as "any balance lands", and later ones as malformed).
 */
internal const val LEGACY_SHIELD_BASELINE_FIELD = Long.MAX_VALUE

/** `ACTION|startedAtMillis|legacyBaseline[|failed]` — the persisted form ([LEGACY_SHIELD_BASELINE_FIELD]). */
internal fun formatInFlightMigration(marker: InFlightMixedFundsMigration): String =
    "${marker.action.name}|${marker.startedAtMillis}|$LEGACY_SHIELD_BASELINE_FIELD" +
        if (marker.failed) "|$IN_FLIGHT_FAILED_FLAG" else ""

private const val IN_FLIGHT_FAILED_FLAG = "failed"

/**
 * Parse the persisted marker, or null when absent/blank or malformed — for
 * PRESENTATION, so a damaged marker never wedges the UI. Spend gates use
 * `inFlightMigrationOrThrow`, which never reads a malformed marker as absent.
 */
internal fun parseInFlightMigration(raw: String?): InFlightMixedFundsMigration? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split('|')
    if (parts.size != 3 && !(parts.size == 4 && parts[3] == IN_FLIGHT_FAILED_FLAG)) return null
    val action = MixedFundsMigrationAction.entries.firstOrNull { it.name == parts[0] } ?: return null
    // A negative start is malformed (it would skew the honesty window).
    val startedAt = parts[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    // The legacy baseline field: any number any build wrote (0, a balance, -1, MAX) is valid and
    // IGNORED — completion never compares a shielded balance. Only a non-number is malformed.
    parts[2].toLongOrNull() ?: return null
    return InFlightMixedFundsMigration(action, startedAt, failed = parts.size == 4)
}

/** Per-route availability ([CoinJoinFundsMigrationService.routeAvailability]). */
data class MixedFundsRoutes(val shieldOperable: Boolean, val combineOperable: Boolean) {
    val any: Boolean get() = shieldOperable || combineOperable
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
    private val applicationScope: CoroutineScope,
    private val instantSendLockDao: InstantSendLockDao
) {
    /** The in-flight completion watcher — see [startInFlightWatcherIfNeeded]. */
    private var inFlightWatcherJob: Job? = null
    private val migrationMutex = Mutex()

    /** Set by [stopForWalletWipe], cleared by [resumeAfterWalletWipe]. */
    @Volatile
    private var wipeStopped = false

    /** Bumped by every [stopForWalletWipe]; a step carries the epoch it started in ([WipeEpoch]). */
    private val wipeEpoch = AtomicInteger()

    /**
     * Consolidation txids already submitted or replayed by THIS process, so
     * the watcher replays a persisted transaction once per process (recovery
     * after process death) and again only on the user's Try again.
     */
    private val replayedConsolidations: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * How many in-flight markers the watcher cleared because the result was
     * VERIFIED visible (this process). The marker also disappears for other
     * reasons — a definitive rejection, a reconciled interrupted intent — so
     * the UI counts a cleared marker as success only when this advanced.
     */
    @Volatile
    var verifiedLandings: Int = 0
        private set

    /**
     * The mixed-funds balance to offer to migrate, or null when there is
     * nothing to do — no readable source, a zero balance, or an L1 view too
     * stale to trust.
     *
     * Source selection by [mixedFundsDuffs]:
     * - POST-CUTOVER: the SDK engine's CoinJoin account 0 confirmed balance
     *   ([sdkCoinJoinConfirmedDuffs]) ALONE. The held dashj wallet is frozen
     *   at its pre-cutover snapshot — stale on an upgraded wallet whose coins
     *   moved since (e.g. shielded from another device), empty on a restored
     *   one — so it is not even read. SDK unreadable → null (no prompt).
     * - PRE-CUTOVER: max of the SDK read (normally unknown — the engine is
     *   passive) and the live dashj CoinJoin keychain ([coinJoinBalanceOrNull]).
     *
     * This is the single source for the prompt gate ([shouldPrompt]), the
     * sheet's offered amount (MixedFundsMigrationViewModel) and the Tools
     * entry, so a prompt is never raised over an amount the sheet cannot show.
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
            // cutoverCommitted() fails closed (false) on a read error, which
            // selects the pre-cutover merge — the conservative pre-cutover
            // gate in shouldPrompt() then refuses a fresh prompt anyway.
            val cutoverCommitted = sdkL1SendService.cutoverCommitted()
            val sdkDuffs = sdkCoinJoinConfirmedDuffs()
            val dashjDuffs = if (cutoverCommitted) {
                null // frozen pre-cutover snapshot — never authoritative here
            } else {
                @Suppress("DEPRECATION")
                walletData.wallet?.let { coinJoinBalanceOrNull(it) }?.value
            }
            val duffs = mixedFundsDuffs(cutoverCommitted, sdkDuffs, dashjDuffs)
            if (duffs == null) {
                log.debug("mixed-funds probe: no readable source (cutoverCommitted={})", cutoverCommitted)
                return@withContext null
            }
            if (duffs > 0L) {
                log.info(
                    "mixed funds detected by {}: sdk={} duffs, dashj={} duffs, cutoverCommitted={}",
                    if ((sdkDuffs ?: 0L) >= duffs) "the SDK engine" else "the dashj wallet",
                    sdkDuffs,
                    dashjDuffs,
                    cutoverCommitted
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
        if (MIXED_FUNDS_PROMPT_HARD_SUPPRESSED || wipeStopped) return false
        if (restorePendingShieldMigration()) return true
        // A FRESH prompt is post-cutover recovery only: pre-cutover dashj
        // still owns L1 and can spend the CoinJoin account itself. An
        // in-flight migration (above) is still presented regardless.
        // cutoverCommitted() fails closed (false) on a read error.
        if (!sdkL1SendService.cutoverCommitted()) return false
        // DONE suppresses only this automatic prompt; later coins stay movable from Tools.
        val alreadyHandled = try {
            readCheckpoint(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) == true
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

    /**
     * Gate for a USER-STARTED fresh move (Tools entry, shield, combine):
     * post-cutover only (pre-cutover dashj still owns L1) and only over an
     * affirmatively read positive SDK CoinJoin balance; both fail closed.
     * Not gated on [DashPayConfig.MIXED_FUNDS_MIGRATION_DONE]: coins that
     * reach the CoinJoin account after a completed migration are never spent
     * by ordinary sends, so the user must still be able to move them.
     */
    private suspend fun userMigrationAllowed(): Boolean =
        sdkL1SendService.cutoverCommitted() && mixedFundsToMigrate() != null

    /**
     * Tools' "Move CoinJoin funds" entry: always for a recorded migration (so
     * it stays resumable), otherwise [userMigrationAllowed] and [anyOptionOperable]. Unlike
     * [shouldPrompt] it ignores DONE, the deferral and the diagnostic toggle.
     */
    suspend fun shouldOfferInTools(): Boolean =
        restorePendingShieldMigration() || (userMigrationAllowed() && anyOptionOperable())

    suspend fun deferMigration(amount: Coin) = unlessWiped(Unit) {
        writeConfig(DashPayConfig.MIXED_FUNDS_MIGRATION_DEFERRED_DUFFS, amount.value)
    }

    /** Continue the persisted consolidation stage, or resume the existing asset lock. */
    suspend fun retryPendingMigration(): MixedFundsMigrationOutcome =
        unlessWiped(MixedFundsMigrationOutcome.NOT_ATTEMPTED) { retryPendingMigrationInner() }

    private suspend fun retryPendingMigrationInner(): MixedFundsMigrationOutcome {
        // A resolved adopted shield has nothing left to resume: back to the choice.
        if (migrationMutex.withLock { releaseResolvedAdoptedShieldLocked() }) {
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        if (!restorePendingShieldMigration() && adoptPendingCoinJoinShield() != PendingCheck.PENDING) {
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        // Checkpoint reads that cannot be completed resume nothing: the record
        // (if any) stays exactly as saved.
        val marker: InFlightMixedFundsMigration
        val stage: ConsolidationStageRead?
        try {
            marker = inFlightMigrationOrThrow() ?: return MixedFundsMigrationOutcome.NOT_ATTEMPTED
            stage = readStage()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("mixed-funds migration checkpoint unreadable; not resuming", t)
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        val hasConsolidation = stage != null
        // A definitive failure has nothing to resume; only a fresh move may follow it.
        if (marker.failed) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (stage is ConsolidationStageRead.Invalid) {
            // Released on proof; else a usable txid is observed again (below), anything else stays expired.
            if (migrationMutex.withLock { releaseInvalidStageLocked() }) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
            if (stage.observation == null) {
                expireMigration()
                return MixedFundsMigrationOutcome.UNCONFIRMED
            }
        }
        if (marker.action != MixedFundsMigrationAction.SHIELD && !hasConsolidation) {
            return MixedFundsMigrationOutcome.UNCONFIRMED
        }
        // A user-requested retry gets a fresh observation window, retaining
        // the SDK's existing lock. A consolidation stage also gets its persisted
        // signed bytes replayed once more ([replayConsolidationIfNeeded]) — never a rebuild.
        replayedConsolidations.clear()
        writeConfig(
            DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT,
            formatInFlightMigration(marker.copy(startedAtMillis = System.currentTimeMillis()))
        )
        restartInFlightWatcher()
        // An explicit retry gets its own resume attempt even when the
        // automatic per-process budget is spent (the lock stays tracked; this
        // never builds a new one).
        val resumed = if (marker.action == MixedFundsMigrationAction.SHIELD) {
            shieldedBalanceService.resumePendingWalletShieldsOnUserRequest()
        } else {
            0
        }
        if (hasConsolidation) {
            return MixedFundsMigrationOutcome.STARTED
        }
        return if (resumed > 0) {
            startInFlightWatcherIfNeeded()
            MixedFundsMigrationOutcome.STARTED
        } else {
            MixedFundsMigrationOutcome.UNCONFIRMED
        }
    }

    /** Is at least ONE of the two migrations currently runnable? See [routeAvailability]. */
    private suspend fun anyOptionOperable(): Boolean = routeAvailability().any

    /**
     * Which routes can run now, so the sheet never offers one whose spend would refuse:
     * the same gates, lock guard and snapshot preconditions the spends check. Fails closed.
     */
    suspend fun routeAvailability(): MixedFundsRoutes {
        val closed = MixedFundsRoutes(shieldOperable = false, combineOperable = false)
        // coinJoinOutputsLocked() never throws (fail closed inside).
        if (withContext(Dispatchers.IO) { sdkL1SendService.coinJoinOutputsLocked() }) return closed
        val snapshot = try {
            coinJoinConsolidationSnapshot(dashSdkService, ::persistedInstantLocks)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to read the CoinJoin snapshot; offering no route", t)
            null
        } ?: return closed
        val shielding = try {
            shieldedBalanceService.isWalletShieldingAvailable()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to probe the shield route; treating it as unavailable", t)
            false
        }
        val gate = consolidationOperable()
        // The same snapshot preconditions shieldMixedFunds / combineIntoUnmixedBalance check.
        val batchable = snapshot.coins.isNotEmpty() && snapshot.batchAmount() > 0L
        val consolidatable = gate && batchable && snapshot.destination != null
        return MixedFundsRoutes(
            shieldOperable = shielding && snapshot.coins.isNotEmpty() &&
                (!snapshot.needsConsolidation || consolidatable),
            combineOperable = gate && batchable && (snapshot.drain || snapshot.destination != null)
        )
    }

    /** Post-cutover and [SdkL1SendService.coinJoinConsolidationGateOpen]; fails closed. */
    private suspend fun consolidationOperable(): Boolean = try {
        sdkL1SendService.cutoverCommitted() && sdkL1SendService.coinJoinConsolidationGateOpen()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to read the consolidation send gate; treating it as closed", t)
        false
    }

    /**
     * OPTION A — shield the mixed funds.
     *
     * Consolidates large CoinJoin accounts within their own address pool before
     * draining into the wallet's Orchard address. [amount] is the UI estimate;
     * the SDK computes the actual lock value after fees.
     */
    suspend fun shieldMixedFunds(amount: Dash): MixedFundsMigrationOutcome =
        unlessWiped(MixedFundsMigrationOutcome.NOT_ATTEMPTED) { shieldMixedFundsInner(amount) }

    private suspend fun shieldMixedFundsInner(amount: Dash): MixedFundsMigrationOutcome = migrationMutex.withLock {
        if (!releaseFailedMigrationLocked()) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        releaseResolvedAdoptedShieldLocked()
        when (resumeOrAdoptPendingMigrationLocked()) {
            PendingCheck.PENDING -> return MixedFundsMigrationOutcome.STARTED
            PendingCheck.UNREADABLE, PendingCheck.DEFERRED -> return MixedFundsMigrationOutcome.NOT_ATTEMPTED
            PendingCheck.NONE -> Unit
        }
        // Defense in depth: the same gate as the Tools entry, whatever the caller.
        if (!userMigrationAllowed()) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (!blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck()) {
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        // Both routes end in an account-wide drain that cannot skip an app-locked input.
        if (withContext(Dispatchers.IO) { sdkL1SendService.coinJoinOutputsLocked() }) {
            log.warn("mixed-funds migration: the CoinJoin account holds app-locked outputs; not shielding")
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        val snapshot = coinJoinConsolidationSnapshot(dashSdkService, ::persistedInstantLocks)
            ?: return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (snapshot.coins.isEmpty()) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (snapshot.needsConsolidation) {
            val destination = snapshot.destination ?: return MixedFundsMigrationOutcome.NOT_ATTEMPTED
            if (snapshot.batchAmount() <= 0L) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
            if (!consolidationOperable()) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
            saveConsolidation(CoinJoinConsolidationState(snapshot.walletId, destination))
            markInFlight(MixedFundsMigrationAction.SHIELD)
            return MixedFundsMigrationOutcome.STARTED
        }
        // Save the recovery presentation before the native call, which may
        // wait for finality indefinitely or outlive this process. The journal
        // says whether the native call was reached: `preparing` left behind by
        // a dead process proves nothing was submitted (see
        // [reconcileInterruptedDirectShield]); `submitted` never does.
        recordShieldInputs(snapshot)
        writeConfig(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE, DIRECT_SHIELD_PREPARING)
        markInFlight(MixedFundsMigrationAction.SHIELD, startWatcher = false)
        val path = coinJoinAccountPath(walletData.networkParameters)
        log.info("mixed-funds migration: shielding {} from {}", amount, path)
        val result = shieldedBalanceService.shieldMixedFundsFromWallet(amount, path, snapshot.walletId) {
            writeConfig(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE, DIRECT_SHIELD_SUBMITTED)
        }
        val outcome = result.toOutcome()
        if (outcome == MixedFundsMigrationOutcome.NOT_ATTEMPTED) {
            clearInFlightMigrationInner()
        } else {
            // Something is (or may be) out — e.g. an existing CoinJoin lock
            // answered without reaching the native call. Never reconcile it away.
            try {
                writeConfig(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE, DIRECT_SHIELD_SUBMITTED)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to record the direct shield as submitted", t)
            }
        }
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
    suspend fun combineIntoUnmixedBalance(): MixedFundsMigrationOutcome =
        unlessWiped(MixedFundsMigrationOutcome.NOT_ATTEMPTED) { combineIntoUnmixedBalanceInner() }

    private suspend fun combineIntoUnmixedBalanceInner(): MixedFundsMigrationOutcome = migrationMutex.withLock {
        if (!releaseFailedMigrationLocked()) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        releaseResolvedAdoptedShieldLocked()
        when (resumeOrAdoptPendingMigrationLocked()) {
            PendingCheck.PENDING -> return MixedFundsMigrationOutcome.STARTED
            PendingCheck.UNREADABLE, PendingCheck.DEFERRED -> return MixedFundsMigrationOutcome.NOT_ATTEMPTED
            PendingCheck.NONE -> Unit
        }
        // Defense in depth: the same gate as the Tools entry, whatever the caller.
        if (!userMigrationAllowed()) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (!blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck()) {
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        val snapshot = coinJoinConsolidationSnapshot(dashSdkService, ::persistedInstantLocks)
            ?: return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (snapshot.coins.isEmpty() || snapshot.batchAmount() <= 0L) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        if (!snapshot.drain && snapshot.destination == null) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        // A closed gate would turn this start into a definitive failure.
        if (!consolidationOperable()) return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        // So would app-locked outputs: every consolidation drain refuses them.
        if (withContext(Dispatchers.IO) { sdkL1SendService.coinJoinOutputsLocked() }) {
            log.warn("mixed-funds migration: the CoinJoin account holds app-locked outputs; not combining")
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
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
            //
            // The UNADVERTISED (engine internal/change) chain. Post-cutover the
            // dashj chain is HELD and its pointer frozen at the restore index, so
            // the plain read would drain the mixed coins onto an address the chain
            // has ALREADY paid (SR-03 / D-003) — and the engine's RECEIVE chain is
            // no better here, because post-cutover its next-unused address is the
            // one the Receive screen is advertising. Draining mixed coins to an
            // address handed to a counterparty is precisely the linkage this
            // destination exists to avoid. The internal chain is never handed out.
            withContext(Dispatchers.IO) { walletData.unadvertisedDestinationLive().toBase58() }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("mixed-funds migration: could not derive an own receive address", t)
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        // Bind the destination to the spending SDK wallet before it is saved.
        if (!isOwnBip44Destination(snapshot.walletId, destination)) {
            log.warn("mixed-funds migration: the derived destination is not the SDK wallet's BIP44 address")
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
                // Strict read: an unreadable marker is never overwritten.
                if (!markerAlreadyRecorded && inFlightMigrationOrThrow() == null) {
                    markInFlight(action)
                }
                else startInFlightWatcherIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to persist the in-flight mixed-funds marker", t)
            }
        }
        try {
            writeConfig(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE, true)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Harmless: the account is (being) emptied, so [shouldPrompt]'s
            // balance check already suppresses the prompt.
            log.warn("failed to persist the mixed-funds migration flag", t)
        }
        return outcome
    }

    // ── The in-flight (post-broadcast) window ─────────────────────────

    /**
     * Resume a migration THIS FLOW recorded: the in-flight marker, or a saved
     * consolidation stage. Both are written only after the user chose a move,
     * so this is safe on the automatic paths (startup prompt, resume after
     * unlock, Tools) — it never starts a migration the user did not choose.
     * A pending CoinJoin-funded lock with no record is NOT adopted here
     * (MO-1099: a restored wallet's lock showed up as "moving your coins"
     * with no tap); see [adoptPendingCoinJoinShield].
     */
    suspend fun restorePendingShieldMigration(): Boolean = unlessWiped(false) { restorePendingShieldMigrationInner() }

    private suspend fun restorePendingShieldMigrationInner(): Boolean {
        // Reconcile only when no migration step is running in this process:
        // a held mutex may be a direct shield between its journal write and
        // the native call.
        if (migrationMutex.tryLock()) {
            try {
                reconcileInterruptedDirectShield()
            } finally {
                migrationMutex.unlock()
            }
        }
        // UNREADABLE counts as pending: "no migration" is only ever reported
        // from checkpoint state that was actually read.
        return restorePendingMigrationRecord() != PendingCheck.NONE
    }

    /**
     * Whether a recorded migration is pending, read WITHOUT the config's
     * IOException rescue: an unreadable checkpoint is [PendingCheck.UNREADABLE],
     * never [PendingCheck.NONE] — a fresh spend must not overwrite a saved
     * (possibly uncertain) record it could not see.
     */
    private suspend fun restorePendingMigrationRecord(lockHeld: Boolean = false): PendingCheck {
        try {
            if (inFlightMigrationOrThrow() != null) {
                startInFlightWatcherIfNeeded()
                return PendingCheck.PENDING
            }
            val consolidation = readCheckpoint(DashPayConfig.MIXED_FUNDS_CONSOLIDATION)
            if (!consolidation.isNullOrBlank()) {
                markStageInFlight(CoinJoinConsolidationState.parse(consolidation))
                return PendingCheck.PENDING
            }
        } catch (malformed: MalformedMigrationMarkerException) {
            // A held mutex is a migration step, which repairs under its own lock.
            if (!lockHeld && !migrationMutex.tryLock()) return PendingCheck.UNREADABLE
            return try {
                repairMalformedMarkerLocked()
            } finally {
                if (!lockHeld) migrationMutex.unlock()
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("mixed-funds migration checkpoint unreadable; treating it as pending", t)
            return PendingCheck.UNREADABLE
        }
        return PendingCheck.NONE
    }

    /**
     * Rewrite a MALFORMED marker into a valid recovery form, never a rebuilt
     * spend. Caller holds [migrationMutex]. A saved stage decides what was
     * spent (the watcher only observes/replays its txid); with no stage it
     * becomes an expired uncertain SHIELD, handled like an uncertain direct
     * shield. Any read/decode failure keeps the marker (UNREADABLE).
     */
    private suspend fun repairMalformedMarkerLocked(): PendingCheck = try {
        val raw = readCheckpoint(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT)
        if (raw.isNullOrBlank() || parseInFlightMigration(raw) != null) {
            // Changed since the first read: re-evaluated on the next pass.
            PendingCheck.UNREADABLE
        } else {
            val stage = readCheckpoint(DashPayConfig.MIXED_FUNDS_CONSOLIDATION)
            if (!stage.isNullOrBlank()) {
                val read = CoinJoinConsolidationState.parse(stage)
                log.warn("mixed-funds migration: malformed marker; recreating it from the saved stage")
                markStageInFlight(read)
                PendingCheck.PENDING
            } else {
                log.warn("mixed-funds migration: malformed marker with no stage; recording it as an uncertain shield")
                markExpired(MixedFundsMigrationAction.SHIELD)
                PendingCheck.PENDING
            }
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("could not repair a malformed mixed-funds marker; keeping it", t)
        PendingCheck.UNREADABLE
    }

    /**
     * USER-ACTION paths only (shield, combine, Restart). Recovers older
     * interrupted shields that predate the early marker write: a pending
     * CoinJoin-funded shield is enough to block either migration, since its
     * reserved inputs may still appear in the confirmed account balance, so
     * the user's move adopts it as its progress instead of spending again.
     * Unrelated BIP44 shield locks are not migration progress.
     */
    private suspend fun adoptPendingCoinJoinShield(): PendingCheck {
        // Only a CONFIRMED zero authorises a fresh spend; unknown is never NONE. No record exists, so
        // every lock is a stranger: one Platform reported consumed (its inputs final on L1) is not
        // this move's progress and does not block it.
        val pending = if (shieldedBalanceService.ensureShieldedReady()) {
            shieldedBalanceService.unsuppressedCoinJoinShieldLockCount()
        } else {
            null
        }
        if (pending == null) {
            log.warn("mixed-funds migration: CoinJoin-lock state unknown (runtime not ready or count unreadable)")
            return PendingCheck.UNREADABLE
        }
        if (pending <= 0) return PendingCheck.NONE
        val recovered = shieldedBalanceService.recoveredCoinJoinShieldLockCount() ?: return PendingCheck.UNREADABLE
        if (recovered >= pending) return resolveRecoveredCoinJoinLocks()
        // Its inputs are unknown: the sentinel keeps it from completing (no proof it was
        // Consumed rather than dropped) and lets a resolved lock return to the choice.
        writeConfig(DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS, ADOPTED_SHIELD_INPUTS)
        markInFlight(MixedFundsMigrationAction.SHIELD)
        log.info("mixed-funds migration: adopted a pending CoinJoin-funded shield as the chosen move's progress")
        return PendingCheck.PENDING
    }

    /**
     * Every pending CoinJoin lock is RecoveredFromChain (5). Never adopted:
     * restore/scan rebuilds every historical shield as 5 (e.g. one completed on
     * another device), which is not this move's progress. The tap resumes them
     * once (a Platform already-consumed answer stops them counting: a stranger's
     * inputs are final on L1, so the fresh drain cannot select them); a fresh
     * spend follows only on a confirmed zero, else DEFERRED: nothing spends or
     * is recorded.
     */
    private suspend fun resolveRecoveredCoinJoinLocks(): PendingCheck {
        log.info("mixed-funds migration: a RecoveredFromChain CoinJoin lock is unresolved; resuming it first")
        shieldedBalanceService.resumePendingWalletShieldsOnUserRequest()
        val left = shieldedBalanceService.unsuppressedCoinJoinShieldLockCount() ?: return PendingCheck.UNREADABLE
        if (left <= 0) return PendingCheck.NONE
        log.warn("mixed-funds migration: {} CoinJoin lock(s) still unresolved; not spending, nothing recorded", left)
        return PendingCheck.DEFERRED
    }

    /**
     * Drop a DEFINITIVELY failed consolidated migration
     * ([InFlightMixedFundsMigration.failed]) so the user may choose again —
     * either destination. Recorded only when nothing was submitted, every
     * consolidation transaction had settled and no CoinJoin-funded lock was
     * pending; this re-checks it (the stage still reads READY and the
     * shielded runtime affirmatively reports no CoinJoin-funded lock pending)
     * and keeps the record on any doubt, including an unavailable runtime.
     *
     * An INVALID stage is released only on [invalidStageReleasable], failed marker or not.
     *
     * @return false when a failed record is present and was kept: the caller
     *   must not spend. A kept invalid stage under a non-failed marker returns
     *   true: the caller's restore reports it PENDING, so nothing spends.
     */
    suspend fun releaseFailedMigration(): Boolean =
        unlessWiped(false) { migrationMutex.withLock { releaseFailedMigrationLocked() } }

    /** Caller holds [migrationMutex]. See [releaseFailedMigration]. */
    private suspend fun releaseFailedMigrationLocked(): Boolean {
        try {
            val marker = inFlightMigrationOrThrow()
            val stage = readStage()
            if (stage is ConsolidationStageRead.Invalid) {
                if (!invalidStageReleasable(stage)) return marker?.failed != true
                log.info("mixed-funds migration: releasing an invalid stage ({}); nothing is out", stage.reason)
            } else {
                if (marker == null || !marker.failed) return true
                if (stage is ConsolidationStageRead.Valid && stage.state.phase != CoinJoinConsolidationState.READY) {
                    return false
                }
                // Release only on a CONFIRMED zero. A failed record submitted nothing (a definitive
                // NotBroadcast never follows a tracked lock of its own), so any lock is a stranger:
                // one Platform reported consumed does not keep it.
                if (!shieldedBalanceService.ensureShieldedReady() ||
                    shieldedBalanceService.unsuppressedCoinJoinShieldLockCount() != 0
                ) {
                    return false
                }
            }
        } catch (malformed: MalformedMigrationMarkerException) {
            // Not a failed record: the caller's restore repairs it under the same lock.
            return true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("could not release a failed mixed-funds migration; keeping its record", t)
            return false
        }
        log.info("mixed-funds migration: releasing a definitively failed attempt for a new choice")
        dropRecord()
        return true
    }

    /**
     * Release an adopted shield ([ADOPTED_SHIELD_INPUTS]) once the runtime
     * affirmatively reports no CoinJoin lock pending, back to the choice: no
     * success, no DONE. Any doubt keeps the record. Caller holds [migrationMutex].
     *
     * A lock Platform reported consumed ([PendingWalletShieldLock.retrySuppressed]) does not keep
     * it, even if it is the adopted lock itself: its inputs are final on L1, so the next move cannot
     * re-spend them, and it is never resumed automatically, so the record would only block every move
     * forever (it can never be proven: an adopted lock is Unproven). A pending lock still keeps it.
     */
    private suspend fun releaseResolvedAdoptedShieldLocked(): Boolean {
        try {
            val marker = inFlightMigrationOrThrow() ?: return false
            if (marker.action != MixedFundsMigrationAction.SHIELD || marker.failed) return false
            if (readCheckpoint(DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS) != ADOPTED_SHIELD_INPUTS) return false
            if (!readCheckpoint(DashPayConfig.MIXED_FUNDS_CONSOLIDATION).isNullOrBlank()) return false
            if (!shieldedBalanceService.ensureShieldedReady()) return false
            if (shieldedBalanceService.unsuppressedCoinJoinShieldLockCount() != 0) return false
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("could not check an adopted mixed-funds shield; keeping its record", t)
            return false
        }
        log.info("mixed-funds migration: the adopted CoinJoin lock is resolved; returning to the choice")
        dropRecord()
        return true
    }

    /** The saved stage, or null when none; throws when unreadable. */
    private suspend fun readStage(): ConsolidationStageRead? =
        readCheckpoint(DashPayConfig.MIXED_FUNDS_CONSOLIDATION)?.takeIf { it.isNotBlank() }
            ?.let { CoinJoinConsolidationState.parse(it) }

    /**
     * Stop the watcher, then clear the record: a still-running watcher of the
     * dropped record must neither delay nor act on a new migration's marker.
     */
    private suspend fun dropRecord() {
        synchronized(this) {
            inFlightWatcherJob?.cancel()
            inFlightWatcherJob = null
        }
        clearInFlightMigrationInner()
    }

    /**
     * Proof that an INVALID stage left nothing out, for the WORST CASE its fields allow:
     * synced, no pending CoinJoin lock, a settled snapshot of the stage's OWN wallet, and
     * a recorded txid final and either a batch ([recordedTxIsBatch]) or a transfer settled
     * on its BIP44 destination ([recordedTransferSettled]); without a usable txid, no tx
     * recorded and no possible final transfer. Anything else stays blocked — reachable
     * only from corruption, which Reset Wallet clears.
     *
     * "No pending CoinJoin lock" sets aside a lock Platform reported consumed, even one this stage
     * may have submitted itself: its inputs are final on L1 (no re-spend) and it is never resumed
     * automatically again, and the release records no success.
     */
    private suspend fun invalidStageReleasable(stage: ConsolidationStageRead.Invalid): Boolean = try {
        val recorded = stage.observation
        stage.walletId != null &&
            (recorded != null || (!stage.unidentifiedTx && !stage.mayBeFinalTransfer)) &&
            blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck() &&
            shieldedBalanceService.ensureShieldedReady() &&
            shieldedBalanceService.unsuppressedCoinJoinShieldLockCount() == 0 &&
            coinJoinConsolidationSnapshot(dashSdkService, ::persistedInstantLocks).let { snapshot ->
                snapshot != null && stage.walletId == snapshot.walletId.lowercase()
            } &&
            (
                recorded == null || (
                    recordedTxFinal(recorded.txid) &&
                        (recordedTxIsBatch(recorded) || recordedTransferSettled(recorded))
                    )
                )
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("could not prove an invalid consolidation stage left nothing out; keeping it", t)
        false
    }

    /** Caller holds [migrationMutex]: re-reads the stage, dropping it only if still invalid and provably releasable. */
    private suspend fun releaseInvalidStageLocked(): Boolean {
        val stage = try {
            readStage()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("consolidation stage unreadable; keeping the record", t)
            return false
        }
        if (stage !is ConsolidationStageRead.Invalid || !invalidStageReleasable(stage)) return false
        log.info("mixed-funds migration: releasing an invalid stage ({}); nothing is out", stage.reason)
        dropRecord()
        return true
    }

    /**
     * The SDK store holds [txid] mined/chain-locked, or with app-persisted
     * InstantSend lock evidence (the mirror never records one: context goes 0 -> 3 at block time).
     */
    private suspend fun recordedTxFinal(txid: String): Boolean {
        val tx = dashSdkService.databaseOrNull()?.transactionDao()
            ?.getByTxid(Utils.HEX.decode(txid.lowercase()).reversedArray())
            ?: return false
        return tx.context in 1..3 || hasPersistedInstantLock(txid)
    }

    /** Caller holds [migrationMutex]. */
    private suspend fun resumeOrAdoptPendingMigrationLocked(): PendingCheck {
        reconcileInterruptedDirectShield()
        val recorded = restorePendingMigrationRecord(lockHeld = true)
        if (recorded != PendingCheck.NONE) return recorded
        return adoptPendingCoinJoinShield()
    }

    /**
     * A DIRECT shield whose process died after recording its intent but
     * before reaching the native funding call left a SHIELD marker that no
     * transaction backs: nothing to watch, and it blocks both moves. Drop it
     * — but only on affirmative evidence of non-submission: the journal still
     * reads `preparing` (written before the marker; upgraded to `submitted`
     * right before the native call), no consolidation stage is recorded, and
     * the SDK tracks no pending CoinJoin-funded lock. Anything else (journal
     * `submitted`/absent/unreadable, runtime not ready, lock count unknown)
     * keeps the marker. Caller must hold [migrationMutex].
     *
     * @return true when the marker was dropped.
     */
    private suspend fun reconcileInterruptedDirectShield(): Boolean {
        try {
            val marker = inFlightMigrationOrThrow() ?: return false
            if (marker.action != MixedFundsMigrationAction.SHIELD) return false
            if (readCheckpoint(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE) != DIRECT_SHIELD_PREPARING) return false
            if (!readCheckpoint(DashPayConfig.MIXED_FUNDS_CONSOLIDATION).isNullOrBlank()) return false
            if (!shieldedBalanceService.ensureShieldedReady()) return false
            // `preparing` proves the native call was never reached, so this record has no lock of
            // its own: a stranger Platform reported consumed does not keep it.
            if (shieldedBalanceService.unsuppressedCoinJoinShieldLockCount() != 0) return false
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("could not reconcile an interrupted mixed-funds shield; keeping its marker", t)
            return false
        }
        log.info("mixed-funds migration: the shield was interrupted before submission; clearing its marker")
        dropRecord()
        return true
    }

    /**
     * The persisted in-flight migration, or null when none is pending —
     * never set, already completed (result visible), or cleared after its
     * timed-out state was acknowledged. Never throws: an unreadable marker
     * also reads null here, so this is for PRESENTATION only. Anything that
     * gates a spend or reports "nothing pending" uses [inFlightMigrationOrThrow].
     */
    suspend fun inFlightMigration(): InFlightMixedFundsMigration? = try {
        inFlightMigrationOrThrow()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to read the in-flight mixed-funds marker", t)
        null
    }

    /**
     * [inFlightMigration] that THROWS when the marker cannot be read (see
     * [readCheckpoint]) or is malformed — never "absent" over a damaged record.
     */
    private suspend fun inFlightMigrationOrThrow(): InFlightMixedFundsMigration? {
        val raw = readCheckpoint(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT)
        if (raw.isNullOrBlank()) return null
        return parseInFlightMigration(raw) ?: throw MalformedMigrationMarkerException()
    }

    /** Read but unparseable — repairable ([repairMalformedMarkerLocked]), unlike a read error. */
    private class MalformedMigrationMarkerException :
        IllegalStateException("malformed mixed-funds migration checkpoint")

    /**
     * Read a migration CHECKPOINT key, keeping "absent" (null) and "could not
     * read" (throws) apart. [DashPayConfig.get] rescues an IOException as empty
     * preferences, which would make a saved, possibly uncertain checkpoint look
     * absent and authorise a fresh spend over it.
     */
    private suspend fun <T> readCheckpoint(key: Preferences.Key<T>): T? =
        dashPayConfig.getPreservingErrors(key)

    /** Reactive mirror of [inFlightMigration] — emits null once the result lands. */
    fun observeInFlightMigration(): Flow<InFlightMixedFundsMigration?> =
        dashPayConfig.observe(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT)
            .map { parseInFlightMigration(it) }

    /**
     * Drop the marker together with its consolidation stage and journal.
     * Called only when nothing is (or may still be) pending: the watcher
     * after a VERIFIED landing, a definitive rejection, a reconciled
     * interrupted intent, a released definitive failure. Never for an
     * uncertain migration — closing the "could not confirm" guidance
     * acknowledges it instead ([acknowledgeUnconfirmedMigration]), keeping
     * the record so nothing in flight is ever rebuilt. The one exception is the
     * user's explicit [closeUnconfirmedMigration] of a provably stuck record.
     */
    suspend fun clearInFlightMigration() = unlessWiped(Unit) { clearInFlightMigrationInner() }

    /** [clearInFlightMigration] for a step that is already in its wipe epoch. */
    private suspend fun clearInFlightMigrationInner() {
        try {
            // First: if it fails the marker stays, so the record never outlives its migration.
            writeConfig(DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS, "")
            writeConfig(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, "")
            writeConfig(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, "")
            writeConfig(DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE, "")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to clear the in-flight mixed-funds marker", t)
        }
    }

    /**
     * The user closed the "could not confirm" guidance of the current
     * (non-failed) marker: stop re-presenting it automatically
     * ([shouldPresentPrompt]) — WITHOUT dropping the marker or its
     * consolidation stage, which still block a fresh spend and keep the
     * migration resumable from Tools. A later window (Try again, a
     * signer-locked retry) has a new start time and is presented again.
     */
    suspend fun acknowledgeUnconfirmedMigration() = unlessWiped(Unit) { acknowledgeUnconfirmedMigrationInner() }

    private suspend fun acknowledgeUnconfirmedMigrationInner() {
        try {
            val marker = inFlightMigrationOrThrow() ?: return
            if (marker.failed) return
            writeConfig(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT, marker.startedAtMillis)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to acknowledge the unconfirmed mixed-funds migration", t)
        }
    }

    /**
     * May the "could not confirm" guidance offer "Close this migration" ([closeUnconfirmedMigration])?
     * Only for the stuck states no proof can ever leave: THIS shield's own settling lock is known
     * and Platform already answered it consumed, or a legacy no-inputs shield held only by
     * Platform-consumed locks over an affirmatively empty CoinJoin account (see
     * [closableRecordOrNull]). False while latched for a wallet wipe and on any doubt.
     */
    suspend fun canCloseUnconfirmedMigration(): Boolean = unlessWiped(false) { closableRecordOrNull() != null }

    /**
     * The user's explicit "Close this migration": drop the record (marker, stage, recorded inputs,
     * journal) so later migrations are no longer blocked. A user DISMISSAL, never proof: no verified
     * landing, no success, no [DashPayConfig.MIXED_FUNDS_MIGRATION_DONE] is recorded. Re-checks the
     * conditions under [migrationMutex]; refused (false) when they no longer hold or during a wipe.
     * The dropped lock(s) stay tracked by the SDK at a final, retry-suppressed status, which never
     * blocks a fresh drain ([PendingWalletShieldLock.blocksFreshDrain]), and with no recorded inputs
     * left none is any record's own lock.
     */
    suspend fun closeUnconfirmedMigration(): Boolean = unlessWiped(false) {
        try {
            migrationMutex.withLock { closeUnconfirmedMigrationLocked() }
        } catch (stopped: StoppedForWalletWipeException) {
            log.warn("mixed-funds migration: close refused, the wallet is being wiped")
            false
        }
    }

    /** Caller holds [migrationMutex]. See [closeUnconfirmedMigration]. */
    private suspend fun closeUnconfirmedMigrationLocked(): Boolean {
        val closable = closableRecordOrNull()
        if (closable == null) {
            log.info("mixed-funds migration: close requested but the record is not closable; keeping it")
            return false
        }
        log.warn(
            "mixed-funds migration: the USER closed an unconfirmed shield ({}): final on L1 and Platform reported " +
                "it consumed, but the credit is unproven. Dropping the record; NO success recorded",
            closable
        )
        dropRecord()
        return true
    }

    /**
     * What makes the current record closable (for the log), or null. Always: a SHIELD marker, not
     * failed, past its honesty window ("could not confirm"). Then either
     *
     * - its recorded inputs are proven spent by ONE shielded top-up lock ([ShieldSettlement.SpentBy];
     *   no invalid stage, no stage of another move) and every row of that lock is
     *   [PendingWalletShieldLock.retrySuppressed]: final on L1 (2, 3 or 5) AND answered
     *   already-consumed by Platform. Only an explicit Try again re-asks; until then
     *   [settlingLockTerminal] cannot pass (a lock that lands reaches Consumed and is not closable); or
     * - a LEGACY record with no inputs ([ShieldSettlement.NotRecorded]) and no stage, whose strict
     *   CoinJoin-lock count is positive (so the legacy proof can never pass) while every counted lock
     *   is retry-suppressed (unsuppressed count 0), on a synced chain with the CoinJoin account
     *   affirmatively empty — the legacy proof's own reads; anything unreadable is not closable.
     *
     * An adopted record (Unproven), a 0/1, Consumed or unknown status, or a final lock Platform has
     * not answered (still resumed; may yet reach Consumed) is not closable.
     */
    private suspend fun closableRecordOrNull(): String? {
        try {
            val marker = inFlightMigrationOrThrow() ?: return null
            if (marker.action != MixedFundsMigrationAction.SHIELD || marker.failed || !marker.isExpired()) return null
            val stage = readStage()
            when (stage) {
                null -> Unit
                is ConsolidationStageRead.Invalid -> return null
                is ConsolidationStageRead.Valid ->
                    if (stage.state.action != MixedFundsMigrationAction.SHIELD) return null
            }
            return when (val settlement = recordedShieldSettlement()) {
                is ShieldSettlement.SpentBy ->
                    "own lock ${settlement.lockTxid}".takeIf { ownLockStuck(settlement.lockTxid) }
                ShieldSettlement.NotRecorded ->
                    if (stage == null && legacyRecordStuck()) "legacy record, Platform-consumed locks only" else null
                ShieldSettlement.Unproven -> null
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("could not check whether the unconfirmed mixed-funds migration is closable; it is not", t)
            return null
        }
    }

    /** Every row of [lockTxid] is [PendingWalletShieldLock.retrySuppressed]; see [closableRecordOrNull]. */
    private suspend fun ownLockStuck(lockTxid: String): Boolean {
        val consumed = readCheckpoint(DashPayConfig.SHIELD_LOCKS_PLATFORM_CONSUMED).orEmpty()
        val rows = settlingLockRows(lockTxid) ?: return false
        return rows.isNotEmpty() && rows.all {
            PendingWalletShieldLock(
                it.outPointHex, it.statusRaw, it.amountDuffs, platformConsumed = it.outPointHex in consumed
            ).retrySuppressed
        }
    }

    /**
     * A legacy no-inputs record the legacy proof can never complete: CoinJoin locks pending, all of
     * them retry-suppressed, on a synced chain with the CoinJoin account affirmatively empty. The
     * counts are read FIRST (as in [shieldMigrationProven]); see [closableRecordOrNull].
     */
    private suspend fun legacyRecordStuck(): Boolean {
        if (!shieldedBalanceService.ensureShieldedReady()) return false
        val strict = shieldedBalanceService.pendingCoinJoinShieldLockCount() ?: return false
        if (strict <= 0) return false
        if (shieldedBalanceService.unsuppressedCoinJoinShieldLockCount() != 0) return false
        return blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck() &&
            sdkCoinJoinConfirmedDuffs() == 0L
    }

    /**
     * Should an AUTOMATIC trigger (startup, resume after unlock) present the
     * sheet? A persisted marker does — processing, failure, or not-yet-seen
     * guidance — unless the user already closed this window's "could not
     * confirm" guidance ([acknowledgeUnconfirmedMigration]); otherwise
     * [shouldPrompt] decides. Tools opens the sheet regardless.
     */
    suspend fun shouldPresentPrompt(): Boolean {
        if (MIXED_FUNDS_PROMPT_HARD_SUPPRESSED || wipeStopped) return false
        // Same reconcile/restore as [shouldPrompt], so a stale interrupted
        // intent is dropped before it can be presented.
        if (restorePendingShieldMigration()) {
            return !isUnconfirmedMigrationAcknowledged()
        }
        return shouldPrompt()
    }

    /**
     * Has the user already closed the "could not confirm" guidance of the
     * CURRENT marker window ([acknowledgeUnconfirmedMigration])? False for a
     * failed marker (never acknowledged away), for no marker, and whenever
     * the marker or acknowledgement cannot be read — the guidance is then
     * presented rather than silently dropped. Every AUTOMATIC re-presentation
     * path honours this, including the unlock path while a call is running.
     */
    suspend fun isUnconfirmedMigrationAcknowledged(): Boolean = try {
        val marker = inFlightMigrationOrThrow()
        marker != null && !marker.failed &&
            readCheckpoint(DashPayConfig.MIXED_FUNDS_MIGRATION_ACKNOWLEDGED_AT) == marker.startedAtMillis
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to read the mixed-funds acknowledgement; presenting", t)
        false
    }

    /**
     * Recreate the marker for a saved stage. Never refused or deferred: completion needs no
     * balance read, only the migration's own proof. An INVALID stage with a usable txid is only
     * observed ([observeInvalidStage]); without one its marker starts expired.
     */
    private suspend fun markStageInFlight(read: ConsolidationStageRead) {
        when (read) {
            is ConsolidationStageRead.Valid -> markInFlight(read.state.action)
            is ConsolidationStageRead.Invalid -> {
                log.warn("mixed-funds migration: invalid consolidation stage ({}); recovering it", read.reason)
                if (read.observation != null) {
                    markInFlight(read.action)
                } else {
                    markExpired(read.action)
                }
            }
        }
    }

    /** An uncertain record shown straight away as "could not confirm"; no watcher. */
    private suspend fun markExpired(action: MixedFundsMigrationAction) {
        val startedAt = System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 1
        writeConfig(
            DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT,
            formatInFlightMigration(InFlightMixedFundsMigration(action, startedAt))
        )
    }

    private suspend fun markInFlight(action: MixedFundsMigrationAction, startWatcher: Boolean = true) {
        val marker = InFlightMixedFundsMigration(action, System.currentTimeMillis())
        writeConfig(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, formatInFlightMigration(marker))
        log.info("mixed-funds migration in flight: {}", action)
        if (startWatcher) startInFlightWatcherIfNeeded()
    }

    /**
     * Start (idempotently) the watcher that clears the in-flight marker once
     * the migration's RESULT is user-visible:
     *
     * - SHIELD — the migration-specific proof only, never a shielded balance:
     *   THIS shield's recorded inputs, each the wallet's own CoinJoin coin, are
     *   spent by ONE shielded top-up asset lock ([recordedShieldSettlement]; an
     *   adopted lock, inputs unknown, never completes here; older records with
     *   none: the CoinJoin account reads affirmatively EMPTY on a synced chain), AND no
     *   OTHER CoinJoin-funded lock is still pending (only Consumed settles; an unknown status
     *   counts; a stranger Platform reported consumed does not — see
     *   [shieldMigrationProven]). The note store is not consulted: during a restore or
     *   rescan it fills in with pre-existing notes, so no balance (or baseline) proves this
     *   migration's credit. The sheet may therefore close before the shielded
     *   balance shows the credit;
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
            if (wipeStopped || inFlightWatcherJob?.isActive == true) return
            inFlightWatcherJob = launchWatcherLocked(previous = null)
        }
    }

    /**
     * Swap in a fresh watcher atomically (same lock as [startInFlightWatcherIfNeeded],
     * so a concurrent start never launches a second); it joins the cancelled
     * one first, so the two never overlap.
     */
    private fun restartInFlightWatcher() {
        synchronized(this) {
            if (wipeStopped) return
            val previous = inFlightWatcherJob
            previous?.cancel()
            inFlightWatcherJob = launchWatcherLocked(previous)
        }
    }

    /**
     * Reset Wallet, before the configs are cleared. Latches the service (no watcher,
     * move, restore or prompt) and ends the wipe epoch, so any later config write by an
     * older step is refused ([writeConfig]), even after [resumeAfterWalletWipe]. Then
     * cancels the watcher and waits up to [WIPE_JOIN_MS] each for it and for a running
     * move: a native step cannot be interrupted, and the wipe must not hang on it.
     */
    suspend fun stopForWalletWipe() {
        wipeStopped = true
        wipeEpoch.incrementAndGet()
        val watcher = synchronized(this) { inFlightWatcherJob.also { inFlightWatcherJob = null } }
        watcher?.cancel()
        if (watcher != null && withTimeoutOrNull(WIPE_JOIN_MS) { watcher.join() } == null) {
            log.warn("wipe: the mixed-funds watcher is still in a native step; its writes are refused")
        }
        if (withTimeoutOrNull(WIPE_JOIN_MS) { migrationMutex.withLock { } } == null) {
            log.warn("wipe: a mixed-funds move is still in a native step; its writes are refused")
        }
        replayedConsolidations.clear()
    }

    /** The wipe finished (configs cleared): the next wallet may migrate. Older steps stay refused. */
    fun resumeAfterWalletWipe() {
        wipeStopped = false
    }

    /** [block] in the current wipe epoch, or [stopped] while latched by [stopForWalletWipe]. */
    private suspend fun <T> unlessWiped(stopped: T, block: suspend () -> T): T = when {
        wipeStopped -> stopped
        currentCoroutineContext()[WipeEpoch] != null -> block()
        else -> withContext(WipeEpoch(wipeEpoch.get())) { block() }
    }

    /** Every config write: refused (unwinding the step) while latched or for a step of an older epoch. */
    private suspend fun <T> writeConfig(key: Preferences.Key<T>, value: T) {
        val epoch = currentCoroutineContext()[WipeEpoch]?.value
        if (wipeStopped || (epoch != null && epoch != wipeEpoch.get())) throw StoppedForWalletWipeException()
        dashPayConfig.set(key, value)
    }

    /** Caller holds the `this` monitor. */
    private fun launchWatcherLocked(previous: Job?): Job = applicationScope.launch(WipeEpoch(wipeEpoch.get())) {
        previous?.join()
        try {
            watchInFlightMigration()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("the in-flight mixed-funds watcher stopped", t)
            expireMigration()
        }
    }

    private suspend fun watchInFlightMigration() {
        while (true) {
            val marker = inFlightMigration() ?: return
            // A definitive failure is not progress: nothing to watch or continue.
            if (marker.failed) return
            val remainingMs = marker.remainingMillis()
            if (remainingMs <= 0L) {
                log.info("in-flight mixed-funds migration ({}) already past the honesty window", marker.action)
                return
            }
            var signerLocked = false
            val landed = withTimeoutOrNull(remainingMs) { watchWindow(marker) { signerLocked = true } } ?: false
            if (signerLocked) {
                // MO-1099: a consolidation or shield step had to sign while the
                // phone was locked, and the Keystore refused the seed. Nothing
                // was broadcast, so this is not a failure: wait and retry the
                // same step until the device is unlocked. Each retry restarts
                // the honesty window — the sheet keeps its processing state
                // instead of reporting an unconfirmed transfer that never
                // existed.
                log.info(
                    "mixed-funds migration ({}): signing needs the device unlocked; retrying in {} s",
                    marker.action, SIGNER_LOCKED_RETRY_MS / 1000L
                )
                delay(SIGNER_LOCKED_RETRY_MS)
                val current = inFlightMigration() ?: return
                writeConfig(
                    DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT,
                    formatInFlightMigration(current.copy(startedAtMillis = System.currentTimeMillis()))
                )
                continue
            }
            if (landed) {
                // The consolidated routes never reach [finish]: record them
                // as handled (like the direct shield) BEFORE dropping the
                // record. On a failed write keep the marker; the next watcher
                // run re-verifies and retries.
                try {
                    writeConfig(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE, true)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.warn("mixed-funds migration ({}): completion flag not saved; keeping marker", marker.action, t)
                    return
                }
                log.info("mixed-funds migration ({}) result is now visible; clearing the in-flight marker", marker.action)
                verifiedLandings++
                clearInFlightMigrationInner()
            } else {
                log.warn(
                    "mixed-funds migration ({}) result did not become visible within {} min",
                    marker.action,
                    MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS / 60_000L
                )
            }
            return
        }
    }

    /**
     * One honesty window: continue the persisted stage, then await the result. A throw (the SDK
     * starting or stopped, a store read) is transient: poll again from the persisted stage. Only a
     * confirmed rebind (STOPPED) stands down; a cancellation, the wipe's refused write included,
     * propagates.
     */
    private suspend fun watchWindow(marker: InFlightMixedFundsMigration, onSignerLocked: () -> Unit): Boolean {
        while (true) {
            try {
                when (continueConsolidation()) {
                    ConsolidationStep.STOPPED -> return false
                    ConsolidationStep.SIGNER_LOCKED -> {
                        onSignerLocked()
                        return false
                    }
                    ConsolidationStep.SETTLED -> return true
                    ConsolidationStep.PROCEED -> Unit
                }
                awaitMigrationResult(marker.action)
                return true
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("mixed-funds watcher: transient failure; polling again", t)
                delay(IN_FLIGHT_POLL_MS)
            }
        }
    }

    /** Suspends until the [action]'s migration result is proven. */
    private suspend fun awaitMigrationResult(action: MixedFundsMigrationAction) {
        when (action) {
            // The migration-specific proof is the whole rule for every marker; no shielded
            // balance is read (a restore/rescan's note sync can raise it with older notes).
            MixedFundsMigrationAction.SHIELD ->
                while (!shieldMigrationProven()) {
                    delay(IN_FLIGHT_POLL_MS)
                }
            // Only an unstaged (older) COMBINE gets here; staged ones end in SETTLED.
            MixedFundsMigrationAction.COMBINE ->
                while (sdkCoinJoinConfirmedDuffs() != 0L) {
                    delay(IN_FLIGHT_POLL_MS)
                }
        }
    }

    /**
     * The SHIELD completion proof: THIS shield's recorded inputs are spent by ONE shielded
     * top-up lock whose OWN row is terminal ([settlingLockTerminal]: the SDK's Consumed status
     * only), and no OTHER CoinJoin-funded lock is pending except strangers Platform reported
     * consumed ([ShieldedBalanceService.unsuppressedCoinJoinShieldLockCount]). Older records
     * with no inputs (no settling lock to tell apart): the CoinJoin account reads affirmatively
     * empty on a synced chain and no CoinJoin-funded lock is pending at all, Platform record or not.
     *
     * Ordered for a store wiped between reads (e.g. a wallet recreation deleting txos and
     * asset_locks, then rebinding): the count is read FIRST, so an emptied table can only
     * make the later settlement reads fail (the spend or the lock row is gone), never pass;
     * the count is then re-confirmed. Any null or missing value is "not proven".
     */
    private suspend fun shieldMigrationProven(): Boolean {
        if (!shieldedBalanceService.ensureShieldedReady()) return false
        // Both read FIRST (see the ordering note above).
        val strict = shieldedBalanceService.pendingCoinJoinShieldLockCount() ?: return false
        if (shieldedBalanceService.unsuppressedCoinJoinShieldLockCount() != 0) return false
        return when (val settlement = recordedShieldSettlement()) {
            // Its OWN lock (by txid) is judged by its own row only; a STRANGER lock Platform
            // reported consumed does not hold it up, every other pending lock does.
            is ShieldSettlement.SpentBy ->
                settlingLockTerminal(settlement.lockTxid) &&
                    shieldedBalanceService.unsuppressedCoinJoinShieldLockCount(settlement.lockTxid) == 0
            ShieldSettlement.Unproven -> false
            // No settling lock to tell apart: any pending lock may be its own, so strict.
            ShieldSettlement.NotRecorded ->
                strict == 0 &&
                    blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck() &&
                    sdkCoinJoinConfirmedDuffs() == 0L &&
                    shieldedBalanceService.pendingCoinJoinShieldLockCount() == 0
        }
    }

    /**
     * The settling lock's own `asset_locks` row(s) on the active wallet exist and every one
     * is the SDK's own Consumed ([ASSET_LOCK_STATUS_CONSUMED]) — nothing else. A Platform
     * already-consumed record is unauthenticated (no proof the credit exists), so it never
     * settles the lock; an unknown status is pending. A missing row, e.g. after the table
     * was wiped, is not proof.
     */
    private suspend fun settlingLockTerminal(lockTxid: String): Boolean = try {
        val rows = settlingLockRows(lockTxid)
        rows != null && rows.isNotEmpty() && rows.all { it.statusRaw == ASSET_LOCK_STATUS_CONSUMED }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("could not read the settling shield lock's row; not complete", t)
        false
    }

    /** [lockTxid]'s shielded top-up `asset_locks` rows on the active wallet; null when the store is unavailable. */
    private suspend fun settlingLockRows(lockTxid: String): List<AssetLockEntity>? {
        val db = dashSdkService.databaseOrNull() ?: return null
        val wallet = activeSdkWalletOrNull() ?: return null
        return db.assetLockDao()
            .observeByWalletAndFundingType(Utils.HEX.decode(wallet), ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP)
            .first()
            .filter { it.outPointHex.substringBefore(':').equals(lockTxid, ignoreCase = true) }
    }

    /** What the recorded shield inputs prove ([recordedShieldSettlement]). */
    private sealed interface ShieldSettlement {
        /** No inputs recorded (an older record): the account-level fallback applies. */
        data object NotRecorded : ShieldSettlement

        /** Recorded, but not (yet) provably spent by one shielded top-up lock. */
        data object Unproven : ShieldSettlement

        /** Spent by the shielded top-up lock [lockTxid] (display hex). */
        data class SpentBy(val lockTxid: String) : ShieldSettlement
    }

    private suspend fun saveConsolidation(state: CoinJoinConsolidationState) {
        writeConfig(DashPayConfig.MIXED_FUNDS_CONSOLIDATION, state.encode())
    }

    /** Before the native call: the coins this shield must spend. */
    private suspend fun recordShieldInputs(snapshot: CoinJoinConsolidationSnapshot) {
        writeConfig(DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS, encodeOutpoints(snapshot.coins.map { it.outpoint }))
    }

    /**
     * Whether the [recordShieldInputs] coins, each the active wallet's own CoinJoin coin
     * ([coinJoinSoleSpender]), are all spent by ONE shielded top-up lock, and which;
     * [ShieldSettlement.NotRecorded] when none were recorded, [ShieldSettlement.Unproven] on any doubt.
     */
    private suspend fun recordedShieldSettlement(): ShieldSettlement {
        val raw = try {
            readCheckpoint(DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("recorded shield inputs unreadable; not complete", t)
            return ShieldSettlement.Unproven
        }
        if (raw.isNullOrBlank()) return ShieldSettlement.NotRecorded
        // Adopted: its inputs are unknown, so nothing proves the lock is Consumed rather than
        // gone (a lock count of 0 is both). Never completed here: once resolved, the next move or
        // Try again returns it to the choice without success ([releaseResolvedAdoptedShieldLocked]).
        if (raw == ADOPTED_SHIELD_INPUTS) return ShieldSettlement.Unproven
        return try {
            val inputs = decodeOutpoints(raw)
            val db = dashSdkService.databaseOrNull() ?: return ShieldSettlement.Unproven
            val wallet = activeSdkWalletOrNull() ?: return ShieldSettlement.Unproven
            val spender = coinJoinSoleSpender(db, wallet, inputs, persistedTxos(db, inputs))
                ?: return ShieldSettlement.Unproven
            val lockTxid = Utils.HEX.encode(spender.reversedArray())
            if (db.assetLockDao().fundingTypeForTxid(lockTxid) == ASSET_LOCK_FUNDING_TYPE_SHIELDED_TOPUP) {
                ShieldSettlement.SpentBy(lockTxid)
            } else {
                ShieldSettlement.Unproven
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("could not read the recorded shield inputs' spender; not complete", t)
            ShieldSettlement.Unproven
        }
    }

    /** The recorded tx, from its signed bytes or the stored row hashing to its txid. */
    private suspend fun recordedTx(db: DashDatabase, state: CoinJoinConsolidationState): Transaction? {
        val params = walletData.networkParameters
        val own = Utils.HEX.decode(state.txid.lowercase()).reversedArray()
        val bytes = state.replayBytesOrNull(params)
            ?: db.transactionDao().getByTxid(own)?.transactionData?.takeIf {
                Transaction(params, it).txId.toString().equals(state.txid, ignoreCase = true)
            }
            ?: return null
        return Transaction(params, bytes)
    }

    /** The recorded tx is a batch of the stage's wallet ([isCoinJoinSelfSpendBatch]), never a final transfer. */
    private suspend fun recordedTxIsBatch(state: CoinJoinConsolidationState): Boolean = try {
        val db = dashSdkService.databaseOrNull()
        val tx = db?.let { recordedTx(it, state) }
        db != null && tx != null && isCoinJoinSelfSpendBatch(
            db, state.walletId, state.destination, tx,
            persistedTxos(db, tx.inputs.map { it.outpoint.bitcoinSerialize() }), walletData.networkParameters
        )
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("could not classify the recorded consolidation tx; treating it as a possible transfer", t)
        false
    }

    /**
     * [transferSettled] for the recorded final transfer, read from its signed
     * bytes or (older stages) the stored row hashing to its txid; false on any doubt.
     */
    private suspend fun recordedTransferSettled(state: CoinJoinConsolidationState): Boolean {
        return try {
            val params = walletData.networkParameters
            val db = dashSdkService.databaseOrNull() ?: return false
            if (state.regularDestination.isEmpty()) return false
            val tx = recordedTx(db, state) ?: return false
            val rows = persistedTxos(db, tx.inputs.map { it.outpoint.bitcoinSerialize() })
            isOwnBip44Destination(state.walletId, state.regularDestination) &&
                transferSettled(db, tx, rows, state.regularDestination, state.walletId, params)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("could not verify the final transfer's persisted settlement; not complete", t)
            false
        }
    }

    private suspend fun persistedTxos(db: DashDatabase, outpoints: List<ByteArray>): List<TxoEntity> =
        outpoints.chunked(PERSISTED_LOCK_QUERY_CHUNK).flatMap { db.txoDao().getByOutpoints(it) }

    /** [ownDestinationAccount] is BIP44 for wallet [walletIdHex]; false on any doubt. */
    private suspend fun isOwnBip44Destination(walletIdHex: String, address: String): Boolean =
        isOwnDestination(walletIdHex, address, OwnDestinationAccount.BIP44)

    /** [ownDestinationAccount] is [expected] for wallet [walletIdHex]; false on any doubt. */
    private suspend fun isOwnDestination(
        walletIdHex: String, address: String, expected: OwnDestinationAccount
    ): Boolean = try {
        val db = dashSdkService.databaseOrNull()
        db != null && ownDestinationAccount(db, walletIdHex, address) == expected
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("could not read the destination's owner; refusing", t)
        false
    }

    /** What [continueConsolidation] left for the watcher to do. */
    private enum class ConsolidationStep {
        /** The persisted stages are done: wait for the migration's result. */
        PROCEED,
        /** The recorded final transfer is proven settled: complete. */
        SETTLED,
        /** Stopped for good, always after recording expired guidance or a definitive failure. */
        STOPPED,
        /** A step could not sign because the device is locked; retry it later. */
        SIGNER_LOCKED
    }

    /**
     * A persisted txid (with its signed bytes) is written before broadcast; an
     * uncertain send is observed and its exact bytes replayed, never rebuilt.
     */
    private suspend fun continueConsolidation(): ConsolidationStep {
        // Strict: an unreadable stage throws (the watcher stands down into the
        // "could not confirm" guidance) instead of reading as "no stage".
        val raw = readCheckpoint(DashPayConfig.MIXED_FUNDS_CONSOLIDATION)
        if (raw.isNullOrBlank()) return ConsolidationStep.PROCEED // Legacy/direct asset-lock recovery.
        var state = when (val read = CoinJoinConsolidationState.parse(raw)) {
            is ConsolidationStageRead.Valid -> read.state
            // Never continued, never built from: observe its txid, if any.
            is ConsolidationStageRead.Invalid -> return observeInvalidStage(read)
        }
        while (true) {
            dashSdkService.ensureStarted()
            // Temporarily unavailable: keep polling within the honesty window.
            val activeWallet = activeSdkWalletOrNull()
            if (activeWallet == null) { delay(IN_FLIGHT_POLL_MS); continue }
            if (activeWallet != state.walletId) return walletRebound(state.walletId)
            if (state.phase == CoinJoinConsolidationState.SHIELDING ||
                state.phase == CoinJoinConsolidationState.SHIELD_PREPARING
            ) {
                if (!shieldedBalanceService.ensureShieldedReady()) {
                    delay(IN_FLIGHT_POLL_MS)
                    continue
                }
                // A stranger Platform reported consumed is not this stage's work: SHIELD_PREPARING never
                // reached the native call, and SHIELDING never rebuilds — it proceeds to the proof,
                // which judges its own lock by its own row.
                val pending = shieldedBalanceService.unsuppressedCoinJoinShieldLockCount()
                if (pending == null) { delay(IN_FLIGHT_POLL_MS); continue }
                if (pending > 0) {
                    if (state.phase != CoinJoinConsolidationState.SHIELDING) {
                        state = state.copy(phase = CoinJoinConsolidationState.SHIELDING)
                        saveConsolidation(state)
                    }
                    shieldedBalanceService.resumePendingWalletShields()
                    return ConsolidationStep.PROCEED
                }
                if (state.phase == CoinJoinConsolidationState.SHIELD_PREPARING) {
                    // Interrupted before the native funding call was reached
                    // (SHIELDING is written right before it): nothing was
                    // submitted, so the drain may be prepared again from a
                    // clean, settled snapshot.
                    state = state.copy(phase = CoinJoinConsolidationState.READY)
                    saveConsolidation(state)
                    continue
                }
                // SHIELDING with no CoinJoin-funded lock row: the funding call
                // was reached, and a missing row does not prove nothing was
                // broadcast (the SDK persists asynchronously). Never build
                // another drain; keep watching for the lock row (resumed
                // above) or the spend to settle, and let the honesty window
                // expire into the "could not confirm" guidance otherwise.
                // Evidence: the recorded inputs spent by a shield lock (older records: an empty account).
                val settled = when (recordedShieldSettlement()) {
                    is ShieldSettlement.SpentBy -> true
                    ShieldSettlement.Unproven -> false
                    ShieldSettlement.NotRecorded ->
                        coinJoinConsolidationSnapshot(dashSdkService, ::persistedInstantLocks)?.coins?.isEmpty() == true
                }
                if (settled) return ConsolidationStep.PROCEED
                delay(IN_FLIGHT_POLL_MS)
                continue
            }
            if (!blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck()) {
                delay(IN_FLIGHT_POLL_MS)
                continue
            }
            if (state.phase == CoinJoinConsolidationState.WAITING || state.phase == CoinJoinConsolidationState.TRANSFERRING) {
                if (!recordedTxFinal(state.txid)) {
                    if (replayConsolidationIfNeeded(state) == ConsolidationReplay.CONFLICT) {
                        // Its inputs went to a different transaction: never
                        // rebuild; stand down into the "could not confirm" guidance.
                        expireMigration()
                        return ConsolidationStep.STOPPED
                    }
                    delay(IN_FLIGHT_POLL_MS)
                    continue
                }
            }
            if (state.phase == CoinJoinConsolidationState.TRANSFERRING) {
                // Final: complete once persistence shows ITS inputs spent by it
                // and ITS BIP44 output. A later CoinJoin coin neither blocks it
                // nor is stranded (a new migration from Tools moves it).
                if (recordedTransferSettled(state)) return ConsolidationStep.SETTLED
                delay(IN_FLIGHT_POLL_MS)
                continue
            }
            val snapshot = coinJoinConsolidationSnapshot(dashSdkService, ::persistedInstantLocks)
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
                state = state.copy(phase = CoinJoinConsolidationState.READY, txid = "", rawTx = "")
                saveConsolidation(state)
            }
            if (state.action == MixedFundsMigrationAction.SHIELD && !snapshot.needsConsolidation) {
                if (!shieldedBalanceService.ensureShieldedReady()) {
                    delay(IN_FLIGHT_POLL_MS)
                    continue
                }
                // Only a CoinJoin-funded lock is this migration's pending work;
                // an unrelated BIP44 shield lock is left to the regular sweep
                // and must not stand in for the drain, nor may a stranger Platform
                // reported consumed (its inputs are final on L1).
                val pending = shieldedBalanceService.unsuppressedCoinJoinShieldLockCount()
                if (pending == null) { delay(IN_FLIGHT_POLL_MS); continue }
                if (pending > 0) {
                    state = state.copy(phase = CoinJoinConsolidationState.SHIELDING)
                    saveConsolidation(state)
                    shieldedBalanceService.resumePendingWalletShields()
                    return ConsolidationStep.PROCEED
                }
                recordShieldInputs(snapshot)
                state = state.copy(phase = CoinJoinConsolidationState.SHIELD_PREPARING)
                saveConsolidation(state)
                val result = shieldedBalanceService.shieldMixedFundsFromWallet(
                    Dash.valueOf(snapshot.coins.sumOf { it.amount }), coinJoinAccountPath(walletData.networkParameters),
                    state.walletId
                ) {
                    // Right before the native call: from here a lock may be out.
                    state = state.copy(phase = CoinJoinConsolidationState.SHIELDING)
                    saveConsolidation(state)
                }
                if (result is SdkWriteResult.NotBroadcast) {
                    saveConsolidation(state.copy(phase = CoinJoinConsolidationState.READY))
                    if (isSignerLockedFailure(result)) return ConsolidationStep.SIGNER_LOCKED
                    // Definitive, with every consolidation settled and no lock
                    // pending: offer the choice again (incl. the wallet-balance
                    // fallback) instead of "could not confirm".
                    failMigration()
                    return ConsolidationStep.STOPPED
                }
                if (state.phase != CoinJoinConsolidationState.SHIELDING) {
                    // Answered without reaching the native call (an existing
                    // CoinJoin lock): still not proof of non-submission.
                    state = state.copy(phase = CoinJoinConsolidationState.SHIELDING)
                    saveConsolidation(state)
                }
                return ConsolidationStep.PROCEED
            }
            // Rebound since the check above: never build; the next pass decides.
            if (snapshot.walletId.lowercase() != state.walletId) { delay(IN_FLIGHT_POLL_MS); continue }
            val amount = snapshot.batchAmount()
            if (amount <= 0L) { failMigration(); return ConsolidationStep.STOPPED }
            val finalTransfer = state.action == MixedFundsMigrationAction.COMBINE && snapshot.drain
            val destination = if (finalTransfer) state.regularDestination else state.destination
            if (finalTransfer && !isOwnBip44Destination(state.walletId, destination)) {
                log.warn("mixed-funds migration: the saved BIP44 destination is not this wallet's; refusing")
                failMigration()
                return ConsolidationStep.STOPPED
            }
            // A batch stays inside the CoinJoin account: never route it to another destination class.
            if (!finalTransfer && !isOwnDestination(state.walletId, destination, OwnDestinationAccount.COIN_JOIN)) {
                log.warn("mixed-funds migration: the saved batch destination is not this wallet's CoinJoin address")
                failMigration()
                return ConsolidationStep.STOPPED
            }
            val result = sdkL1SendService.consolidateCoinJoin(
                state.walletId, destination, amount, snapshot.drain
            ) { bytes ->
                val txid = validateCoinJoinConsolidation(bytes, snapshot, destination, walletData.networkParameters,
                    minimumInputs = if (finalTransfer) 1 else 2)
                // The signed bytes go into the SAME write as the txid, before
                // broadcast: a process that dies before the native broadcast
                // can then replay exactly this transaction.
                state = state.copy(phase = if (finalTransfer) CoinJoinConsolidationState.TRANSFERRING
                    else CoinJoinConsolidationState.WAITING, txid = txid, rawTx = Utils.HEX.encode(bytes))
                saveConsolidation(state)
                log.info("CoinJoin consolidation prepared: {} ({} available inputs)", txid, snapshot.coins.size)
            }
            if (result is SdkWriteResult.NotBroadcast) {
                state = state.copy(phase = CoinJoinConsolidationState.READY, txid = "", rawTx = "")
                saveConsolidation(state)
                if (isSignerLockedFailure(result)) return ConsolidationStep.SIGNER_LOCKED
                failMigration()
                return ConsolidationStep.STOPPED
            }
            // Submitted by this process: no automatic replay needed here.
            if (result is SdkWriteResult.Broadcast) replayedConsolidations.add(state.txid.lowercase())
            // Ambiguous without a prepared txid cannot safely start another build.
            if (state.phase !in setOf(CoinJoinConsolidationState.WAITING, CoinJoinConsolidationState.TRANSFERRING)) {
                expireMigration(); return ConsolidationStep.STOPPED
            }
            delay(IN_FLIGHT_POLL_MS)
        }
    }

    /** The SDK's single active wallet id (lowercase), or null while the SDK has none to offer. */
    private fun activeSdkWalletOrNull(): String? =
        dashSdkService.walletManagerOrNull()?.wallets?.value?.keys?.singleOrNull()?.lowercase()

    /** The SDK is bound to another wallet: never build for it; stand down into the guidance, keeping the record. */
    private suspend fun walletRebound(recorded: String): ConsolidationStep {
        log.warn("mixed-funds migration: the SDK wallet is no longer {}…; standing down", recorded.take(8))
        expireMigration()
        return ConsolidationStep.STOPPED
    }

    /**
     * An INVALID stage is never built from or continued: only its recorded txid
     * is observed/replayed. Only [recordedTransferSettled] completes it; anything
     * else ends in the "could not confirm" guidance.
     */
    private suspend fun observeInvalidStage(read: ConsolidationStageRead.Invalid): ConsolidationStep {
        val state = read.observation
        if (state == null) {
            expireMigration()
            return ConsolidationStep.STOPPED
        }
        while (true) {
            dashSdkService.ensureStarted()
            val activeWallet = activeSdkWalletOrNull()
            if (activeWallet == null) { delay(IN_FLIGHT_POLL_MS); continue }
            if (activeWallet != state.walletId) return walletRebound(state.walletId)
            if (!blockchainStateProvider.getState().isSyncedEnoughForMixedFundsCheck()) {
                delay(IN_FLIGHT_POLL_MS)
                continue
            }
            if (!recordedTxFinal(state.txid)) {
                if (replayConsolidationIfNeeded(state) == ConsolidationReplay.CONFLICT) {
                    expireMigration()
                    return ConsolidationStep.STOPPED
                }
                delay(IN_FLIGHT_POLL_MS)
                continue
            }
            // Nothing to complete: Try again releases it by [invalidStageReleasable]'s proof.
            if (state.regularDestination.isEmpty() || recordedTxIsBatch(state)) {
                expireMigration()
                return ConsolidationStep.STOPPED
            }
            // Possibly the final transfer: complete only on its proven
            // settlement (own BIP44 output persisted); the window bounds it.
            if (recordedTransferSettled(state)) return ConsolidationStep.SETTLED
            delay(IN_FLIGHT_POLL_MS)
        }
    }

    /**
     * Replay the persisted signed bytes of an unsettled WAITING/TRANSFERRING
     * consolidation — once per process per txid (recovery after process
     * death) and again after the user's Try again. Never rebuilds:
     * - legacy state without bytes → nothing to replay (observation only);
     * - bytes that do not hash to the recorded txid → refused;
     * - an input already spent by a DIFFERENT transaction (locally, or as
     *   the network's answer) → [ConsolidationReplay.CONFLICT];
     * - an "already known" answer counts as submitted.
     *
     * @return the replay outcome, or null when nothing was attempted.
     */
    private suspend fun replayConsolidationIfNeeded(state: CoinJoinConsolidationState): ConsolidationReplay? {
        val txid = state.txid.lowercase()
        if (txid.isEmpty() || state.rawTx.isEmpty()) return null
        if (!replayedConsolidations.add(txid)) return null
        val bytes = state.replayBytesOrNull(walletData.networkParameters)
        if (bytes == null) {
            log.error("CoinJoin consolidation {}: persisted bytes do not match the txid; refusing to replay", txid)
            return null
        }
        if (inputsSpentElsewhere(bytes, txid)) {
            log.warn("CoinJoin consolidation {}: an input is spent by a different transaction; not replaying", txid)
            return ConsolidationReplay.CONFLICT
        }
        log.info("CoinJoin consolidation {}: replaying the persisted signed transaction", txid)
        return sdkL1SendService.rebroadcastCoinJoinConsolidation(bytes, txid)
    }

    /**
     * Does the SDK store record any input of [rawTx] as spent by a
     * transaction OTHER than [txid]? A read failure answers false: the
     * replay itself is idempotent, and the network's conflict answer still
     * fails closed.
     */
    private suspend fun inputsSpentElsewhere(rawTx: ByteArray, txid: String): Boolean = try {
        val tx = org.bitcoinj.core.Transaction(walletData.networkParameters, rawTx)
        val own = Utils.HEX.decode(txid).reversedArray()
        val rows = dashSdkService.databaseOrNull()?.txoDao()
            ?.getByOutpoints(tx.inputs.map { it.outpoint.bitcoinSerialize() })
            .orEmpty()
        rows.any { row -> row.spendingTxid?.let { !it.contentEquals(own) } == true }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("could not read the consolidation inputs' spenders", t)
        false
    }

    private suspend fun expireMigration() {
        val marker = inFlightMigration() ?: return
        writeConfig(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT,
            formatInFlightMigration(marker.copy(startedAtMillis = System.currentTimeMillis() - MIXED_FUNDS_IN_FLIGHT_TIMEOUT_MS - 1)))
    }

    /** Record a DEFINITIVE failure of a consolidated migration — see [InFlightMixedFundsMigration.failed]. */
    private suspend fun failMigration() {
        val marker = inFlightMigration() ?: return
        writeConfig(DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT, formatInFlightMigration(marker.copy(failed = true)))
    }

    /**
     * The subset of [txids] (lowercase display hex) with an app-persisted
     * InstantSend lock. A read failure answers none: the stage then waits for
     * a block, as before, and never advances on missing evidence.
     */
    private suspend fun persistedInstantLocks(txids: Collection<String>): Set<String> {
        if (txids.isEmpty()) return emptySet()
        return try {
            txids.chunked(PERSISTED_LOCK_QUERY_CHUNK)
                .flatMap { instantSendLockDao.getLockedTxIds(it) }
                .mapTo(HashSet()) { it.lowercase() }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("persisted InstantSend lock read failed; waiting for a block instead", t)
            emptySet()
        }
    }

    private suspend fun hasPersistedInstantLock(txid: String): Boolean =
        txid.lowercase().let { it in persistedInstantLocks(listOf(it)) }

    private fun SdkWriteResult<*>.toOutcome(): MixedFundsMigrationOutcome = when (this) {
        is SdkWriteResult.Broadcast -> MixedFundsMigrationOutcome.STARTED
        is SdkWriteResult.NotBroadcast -> MixedFundsMigrationOutcome.NOT_ATTEMPTED
        is SdkWriteResult.Ambiguous -> MixedFundsMigrationOutcome.UNCONFIRMED
    }

    companion object {
        private val log = LoggerFactory.getLogger(CoinJoinFundsMigrationService::class.java)

        /**
         * Fee headroom taken off the UI's shield ESTIMATE only (same figure
         * as [SEND_ALL_FEE_RESERVE_DUFFS]). The SDK drains the whole account
         * into the lock with no change, so nothing is left on `m/9'`.
         */
        const val L1_FEE_RESERVE_DUFFS = SEND_ALL_FEE_RESERVE_DUFFS

        /** Keeps each persisted IS-lock lookup under SQLite's 999-variable cap. */
        private const val PERSISTED_LOCK_QUERY_CHUNK = 500

        /**
         * COMBINE completion poll cadence — the `accountBalances` snapshot
         * has no push feed, so the watcher re-reads it on this interval.
         */
        private const val IN_FLIGHT_POLL_MS = 15_000L

        /** How often a step blocked by a locked device is retried. */
        private const val SIGNER_LOCKED_RETRY_MS = 30_000L

        /** Result of reading the recorded-migration checkpoint; DEFERRED: see [resolveRecoveredCoinJoinLocks]. */
        private enum class PendingCheck { NONE, PENDING, UNREADABLE, DEFERRED }

        /** [DashPayConfig.MIXED_FUNDS_DIRECT_SHIELD_PHASE] values. */
        internal const val DIRECT_SHIELD_PREPARING = "preparing"
        internal const val DIRECT_SHIELD_SUBMITTED = "submitted"

        /** How long [stopForWalletWipe] waits for the watcher, then for a running move. */
        internal const val WIPE_JOIN_MS = 10_000L

        /** [DashPayConfig.MIXED_FUNDS_SHIELD_INPUTS] of an adopted lock (no outpoints known). */
        internal const val ADOPTED_SHIELD_INPUTS = "adopted"
    }
}

/** The wipe epoch a migration step started in (see [CoinJoinFundsMigrationService.stopForWalletWipe]). */
private class WipeEpoch(val value: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WipeEpoch>
}

/** A config write refused after a wallet wipe began: unwinds the step like a cancellation. */
internal class StoppedForWalletWipeException : CancellationException("mixed-funds migration stopped for a wallet wipe")
