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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fee headroom, in duffs, required ON TOP of the asset-lock amount for the
 * funding-eligibility preflight to report "fundable". Same sizing rationale
 * as [SEND_ALL_FEE_RESERVE_DUFFS]: at the asset-lock builder's
 * default rate (`DEFAULT_FEE_PER_KB` = 1000 duffs/kB) the fee is
 * `~44 + 148·n_inputs` duffs, so 10 000 covers ~67 inputs — far beyond a
 * typical wallet. A wallet that exceeds it anyway fails the REAL build
 * pre-broadcast exactly as before; the preflight is advisory-conservative,
 * never a funds-safety boundary.
 */
const val ASSET_LOCK_PREFLIGHT_FEE_HEADROOM_DUFFS = 10_000L

/**
 * `transactions.context` codes that are LOCK EVIDENCE for finality
 * (`0=mempool, 1=instantSend, 2=inBlock, 3=chainLocked` — the same code
 * table [L1ShadowSyncService]'s event dedupe documents):
 * `1` = the engine recorded a received IS lock, `3` = the tx sits in a
 * chain-locked block. Needed because the AAR's `txos` mirror writer
 * NEVER sets `txos.isInstantLocked` on Android (verified on-device, S21
 * `dash-sdk.db`: the flag is 0 on every row ever written while
 * `transactions.context = 3` for the same txids) — the per-output flag is
 * dead, so per-TX context is the only surviving IS-lock evidence.
 * `2` (inBlock, not chain-locked) is deliberately NOT lock evidence: a
 * plain block can reorg, and a mined output's `isConfirmed` flag IS
 * maintained correctly (same on-device evidence), so `2` adds nothing the
 * confirmed term doesn't already cover.
 */
internal const val TX_CONTEXT_INSTANT_SEND = 1
internal const val TX_CONTEXT_CHAIN_LOCKED = 3

/**
 * The join spine both asset-lock queries below share, so they stay exact
 * complements of one another over the SAME row set. Every join is LEFT:
 * a term that needs a missing row must evaluate to false for that row, not
 * silently drop it (the account join was the one that dropped a pre-block
 * receive outright — see [UNCLASSIFIED_ASSET_LOCK_DUFFS_SQL]).
 */
private const val ASSET_LOCK_TXO_JOINS =
    "FROM txos t " +
        "LEFT JOIN core_addresses ca ON ca.address = t.address " +
        "LEFT JOIN accounts a ON a.id = COALESCE(t.accountId, ca.accountId) " +
        "LEFT JOIN transactions tx ON tx.txid = t.txid"

/** Rows the engine could spend at all: unspent, not mid-spend, unreserved, non-coinbase. */
private const val UNSPENT_SELECTABLE_TERMS =
    "t.isSpent = 0 AND t.spendingTxid IS NULL AND t.isLocked = 0 AND t.isCoinbase = 0"

/**
 * Finality as the MIRROR can express it. `COALESCE` keeps the term
 * three-valued-free (a txo whose `transactions` row is absent must read
 * false, not NULL — otherwise `NOT (...)` swallows the row instead of
 * negating it).
 */
private const val MIRROR_FINAL_TERM =
    "(t.isConfirmed = 1 OR t.isInstantLocked = 1 " +
        "OR COALESCE(tx.context, -1) IN ($TX_CONTEXT_INSTANT_SEND, $TX_CONTEXT_CHAIN_LOCKED))"

/**
 * Finality EXTENDED with the app's own persisted IS-lock evidence
 * (`instant_send_locks`, written by
 * [CutoverUiDataService.applyInstantLock] the moment the engine reports a
 * lock): the mirror's own finality terms OR `t.txid` in the given number of
 * bound wire-order txid blobs. With [lockCount] = 0 this IS
 * [MIRROR_FINAL_TERM] — the pre-existing query, byte-identical.
 *
 * This is what tightens the pre-block fail-open: an IS-locked receive used
 * to be invisible to the mirror for a whole block (`txos.isInstantLocked`
 * dead, `transactions.context` 0→3 skipping 1), so a freshly funded wallet
 * read as "no evidence" and every caller failed open. With the persisted
 * lock joined in, the same wallet reads as ELIGIBLE — a confident yes —
 * while a wallet whose classified funds genuinely cannot cover the lock
 * still gates.
 */
private fun mirrorFinalTermSql(lockCount: Int): String =
    if (lockCount == 0) {
        MIRROR_FINAL_TERM
    } else {
        val placeholders = (1..lockCount).joinToString(",") { "?" }
        "(t.isConfirmed = 1 OR t.isInstantLocked = 1 " +
            "OR COALESCE(tx.context, -1) IN ($TX_CONTEXT_INSTANT_SEND, $TX_CONTEXT_CHAIN_LOCKED) " +
            "OR t.txid IN ($placeholders))"
    }

/** The one account a non-shielded asset lock funds from: Standard/BIP44, index 0. */
private const val BIP44_ACCOUNT_0_TERM =
    "a.accountType = 0 AND a.standardTag = 0 AND a.accountIndex = 0"

/**
 * The asset-lock eligibility SQL over the SDK's Room mirror — extracted as
 * a constant so the host-level regression tests run the EXACT production
 * query against the AAR's own schema. See the [SdkAssetLockFundingPreflight]
 * class KDoc for the source-level trace of every predicate term, and
 * [TX_CONTEXT_INSTANT_SEND]/[TX_CONTEXT_CHAIN_LOCKED] for why finality is
 * `isConfirmed OR isInstantLocked OR transactions.context IN (1, 3)`.
 *
 * Account attribution takes `COALESCE(t.accountId, ca.accountId)` — on
 * Android `txos.accountId` is NULL on every row (on-device evidence), so
 * the `core_addresses` address join is the live path, but a future AAR
 * that starts populating the column must not break attribution when an
 * address row is missing (hence the LEFT joins). A row with NEITHER
 * resolvable is not eligible — but it is not a shortfall either; it falls
 * to [UNCLASSIFIED_ASSET_LOCK_DUFFS_SQL], because on Android an
 * unattributable row is what a not-yet-mined receive looks like, not a
 * coin the engine has refused to route.
 */
internal val ELIGIBLE_ASSET_LOCK_DUFFS_SQL = eligibleAssetLockDuffsSql(lockCount = 0)

/**
 * The eligibility sum with [lockCount] persisted IS-lock txid blobs bound
 * into the finality term ([mirrorFinalTermSql]) — args: walletId, then the
 * wire-order txid blobs. `lockCount = 0` is the pre-existing constant.
 */
internal fun eligibleAssetLockDuffsSql(lockCount: Int): String =
    "SELECT COALESCE(SUM(t.amount), 0) $ASSET_LOCK_TXO_JOINS " +
        "WHERE t.walletId = ? " +
        "AND $UNSPENT_SELECTABLE_TERMS " +
        "AND ${mirrorFinalTermSql(lockCount)} " +
        "AND $BIP44_ACCOUNT_0_TERM"

/**
 * The complement of [ELIGIBLE_ASSET_LOCK_DUFFS_SQL] over the same rows:
 * unspent wallet value the eligibility query dropped ONLY because the
 * mirror has not classified it yet — either no finality signal has been
 * written, or no account row exists to attribute it through. Deliberately
 * excludes anything attributed to a NON-BIP44-account-0 account: that is a
 * settled fact about scope, not a gap in the mirror.
 *
 * Exists because the mirror describes a PRE-BLOCK receive not at all
 * (S21 mainnet, 11.10.67, wallet holding one 0.09401442 DASH receive):
 * the engine reported the transaction at 17:18:28 and its InstantSend lock
 * 0.6s later, yet `core_addresses` — the only attribution route on Android,
 * `txos.accountId` being permanently NULL — was not written until the block
 * landed at 17:27:16, and no finality signal flipped before then either
 * (`txos.isInstantLocked` is dead, and `transactions.context` went 0 → 3,
 * never through 1). For those nine minutes the eligibility sum was 0 and
 * every asset-lock-funded flow refused a visibly funded wallet.
 *
 * A zero sum from a mirror that cannot yet classify the wallet's coins is
 * not evidence of a shortfall — see [assetLockFundingVerdict].
 */
internal val UNCLASSIFIED_ASSET_LOCK_DUFFS_SQL = unclassifiedAssetLockDuffsSql(lockCount = 0)

/**
 * The unclassified sum with the SAME [lockCount] lock blobs as
 * [eligibleAssetLockDuffsSql] (args: walletId, then the wire-order txid
 * blobs), keeping the two queries EXACT COMPLEMENTS over the unspent
 * selectable rows minus settled non-BIP44 attributions:
 *
 * `a.id IS NULL` (unattributable — the pre-block shape) counts REGARDLESS
 * of finality, and an attributed BIP44-account-0 row counts only while NOT
 * final. Structured this way (instead of the historical
 * `NOT final AND (a.id IS NULL OR bip44)`) because the persisted IS-lock
 * evidence makes "final but not yet attributable" a REACHABLE state: the
 * lock lands seconds after the receive while `core_addresses` is only
 * written with the block. Under the historical shape such a row satisfied
 * neither query — vanishing from BOTH sums and fabricating a false "proven
 * shortfall" on a visibly funded wallet — whereas it is exactly the
 * "mirror knows the coin is final but cannot yet route it" case the
 * unclassified bucket exists for ([assetLockFundingVerdict] then answers
 * `null`, fail open, as before).
 */
internal fun unclassifiedAssetLockDuffsSql(lockCount: Int): String =
    "SELECT COALESCE(SUM(t.amount), 0) $ASSET_LOCK_TXO_JOINS " +
        "WHERE t.walletId = ? " +
        "AND $UNSPENT_SELECTABLE_TERMS " +
        "AND (a.id IS NULL OR (($BIP44_ACCOUNT_0_TERM) AND NOT ${mirrorFinalTermSql(lockCount)}))"

/**
 * What the mirror can say about asset-lock fundability, as TWO numbers —
 * because "eligible = 0" and "eligible = 1449" are different kinds of
 * statement and the gate must not treat them alike.
 */
data class AssetLockFundingEvidence(
    /**
     * Final, BIP44-account-0 duffs — value the engine's coin selection
     * would certainly accept ([ELIGIBLE_ASSET_LOCK_DUFFS_SQL]).
     */
    val eligibleDuffs: Long,
    /**
     * Unspent duffs the mirror holds but cannot yet classify
     * ([UNCLASSIFIED_ASSET_LOCK_DUFFS_SQL]) — value that may or may not be
     * selectable; only the engine knows.
     */
    val unclassifiedDuffs: Long
)

/** Eligible duffs: SUM over [ELIGIBLE_ASSET_LOCK_PREDICATE_SQL]. */
internal const val ELIGIBLE_ASSET_LOCK_DUFFS_SQL =
    "SELECT COALESCE(SUM(t.amount), 0) FROM txos t " +
        ELIGIBLE_ASSET_LOCK_PREDICATE_SQL

/**
 * COUNT twin of [ELIGIBLE_ASSET_LOCK_DUFFS_SQL] — the number of UTXOs a
 * fresh asset-lock build can select. Sizes the fee reserve a MAX
 * ("spend everything") top-up withholds on its one adjusted retry: the fee
 * is ~148 bytes per INPUT, and this is the exact input population, from the
 * engine that will do the selecting. (dashj's spendableUtxoCount() is the
 * wrong ruler here: it counts coins the asset lock can never select —
 * CoinJoin, other accounts, non-final — and post-cutover it can be stale.)
 */
internal val ELIGIBLE_ASSET_LOCK_UTXO_COUNT_SQL = eligibleAssetLockUtxoCountSql(lockCount = 0)

/**
 * The COUNT with the SAME [lockCount]-parameterized finality term and spine
 * as [eligibleAssetLockDuffsSql] (args: walletId, then the wire-order txid
 * blobs) — the two queries must count/sum the SAME population, or the MAX
 * fee reserve gets sized from a different UTXO set than the one the sum
 * (and the engine's selection) sees.
 */
internal fun eligibleAssetLockUtxoCountSql(lockCount: Int): String =
    "SELECT COUNT(*) $ASSET_LOCK_TXO_JOINS " +
        "WHERE t.walletId = ? " +
        "AND $UNSPENT_SELECTABLE_TERMS " +
        "AND ${mirrorFinalTermSql(lockCount)} " +
        "AND $BIP44_ACCOUNT_0_TERM"

/**
 * Pure coverage predicate for the preflight (host-JVM testable): can
 * [eligibleDuffs] of asset-lock-eligible funds cover a lock of
 * [requiredDuffs] plus fee headroom?
 */
internal fun assetLockFundingCovers(
    eligibleDuffs: Long,
    requiredDuffs: Long,
    headroomDuffs: Long = ASSET_LOCK_PREFLIGHT_FEE_HEADROOM_DUFFS
): Boolean = eligibleDuffs >= requiredDuffs + headroomDuffs

/**
 * The preflight's THREE-valued verdict for a lock of [requiredDuffs] —
 * `true` fundable, `false` a proven shortfall, `null` no evidence either
 * way (callers fail open).
 *
 * The `null` arm is the whole point: a shortfall is only PROVEN when the
 * mirror has classified enough of the wallet to rule the funding out. As
 * long as unclassified value could close the gap, the mirror is simply
 * behind the engine (see [UNCLASSIFIED_ASSET_LOCK_DUFFS_SQL]) and the
 * preflight must say nothing — the real build stays the authority.
 *
 * This keeps the S22 gate that motivated the preflight: there the mirror
 * had classified the wallet down to 1449 duffs of dust against a 0.03 DASH
 * fee, with nothing unclassified left to close a 3 000 000-duff gap, so the
 * verdict is still `false`.
 */
internal fun assetLockFundingVerdict(
    evidence: AssetLockFundingEvidence,
    requiredDuffs: Long,
    headroomDuffs: Long = ASSET_LOCK_PREFLIGHT_FEE_HEADROOM_DUFFS
): Boolean? = when {
    assetLockFundingCovers(evidence.eligibleDuffs, requiredDuffs, headroomDuffs) -> true
    assetLockFundingCovers(
        evidence.eligibleDuffs + evidence.unclassifiedDuffs,
        requiredDuffs,
        headroomDuffs
    ) -> null
    else -> false
}

/**
 * PRE-FLIGHT funding-eligibility check for every SDK operation funded by a
 * fresh L1 ASSET LOCK (identity registration / username creation, DIP-13
 * invitation vouchers, identity top-ups): would the engine's asset-lock coin
 * selection find enough eligible funds — BEFORE the user commits to a flow
 * that fails minutes later at build time?
 *
 * ## Why the DISPLAY balance is the wrong gate (the S22 repro)
 *
 * Observed live (11.10.41): displayed balance ~0.994 DASH, username creation
 * accepted, ~30s processing dialog, then
 * `Insufficient funds: available 1449, required 3000000` from the asset-lock
 * build. Two structural gaps between the display balance and the asset-lock
 * selection, both traced through the pinned engine sources:
 *
 * 1. **Finality.** The asset-lock builder
 *    (`ManagedWalletInfo::build_asset_lock_with_signer`, key-wallet
 *    `asset_lock_builder.rs`) applies `require_final_inputs`
 *    (`transaction_builder.rs`): only `is_confirmed || is_instantlocked`
 *    UTXOs are selectable — per DIP-0010 an asset lock must itself receive
 *    an IS-lock, so it must never spend non-final inputs. The CONFIRMED
 *    balance bucket (`managed_core_funds_account.rs` `update_balance`)
 *    additionally counts `is_trusted` mempool change — so the display
 *    balance can cover the fee while the selection cannot.
 * 2. **Account scope.** The build funds from the ONE BIP44 standard account
 *    at index 0 (`standard_bip44_accounts[account_index]`); wallet-level
 *    display balance aggregates every funds account (DashPay receival,
 *    CoinJoin, platform-address accounts…).
 *
 * A dry-run through `buildSignedPaymentWithToken(fundingPath = null)` was
 * considered and REJECTED: it seeds the same BIP44 account via the same
 * `set_funding`, but does NOT apply `require_final_inputs`
 * (`CoreWallet::finalize_signed_payment_from_funding_path`, platform-wallet
 * `core/send.rs`) — so it would have PASSED the very wallet state that
 * produced the S22 failure, and it disturbs real sends by transiently
 * reserving UTXOs.
 *
 * ## What this preflight computes instead
 *
 * The exact eligibility predicate of the asset-lock selection, evaluated
 * over the SDK's own Room mirror of the engine's UTXO set (the `txos`
 * table, written from the engine's changesets — same source of truth):
 *
 * `Σ amount` over unspent BIP44-account-0 TXOs where
 * `!isLocked && (isConfirmed || isInstantLocked || txContext ∈ {IS, CL})
 * && coinbase-mature` — mirroring `require_final_inputs` +
 * `Utxo::is_spendable`, with one Android-mirror correction: the AAR's txo
 * writer never sets `txos.isInstantLocked` (dead flag, see
 * [TX_CONTEXT_INSTANT_SEND]), so IS-lock finality is read from the
 * per-transaction `transactions.context` instead — otherwise every fresh
 * receive waits a full block (observed on S21: a 1.0 tDASH faucet receive
 * gated username creation for ~25 minutes despite its IS lock).
 * Account scoping routes `COALESCE(txos.accountId,
 * core_addresses.accountId)` (the address join is the live path — the
 * AAR persists `txos.accountId` as NULL on every Android row, the same
 * blindness class [SdkTxContactResolver] compensates for), restricted to
 * `accountType = 0 (Standard), standardTag = 0 (BIP44), accountIndex = 0` —
 * the account every non-shielded asset lock funds from. Rows with a linked
 * `spendingTxid` are excluded (mid-spend; the SDK's restore builder applies
 * the same stale-flag guard).
 *
 * Known deltas vs the live engine, both bounded and documented:
 * - in-memory UTXO **reservations** (concurrent in-flight builds) are not
 *   persisted → transiently fail-OPEN;
 * - the Room mirror **lags a self-send** by moments (the changeset write) →
 *   transiently fail-open. Feasibility itself is strategy-independent: the
 *   engine reports `InsufficientFunds` from the eligible SUM vs
 *   target+fee (`coin_selection.rs`), which is what this reproduces.
 *
 * ## Why the eligible sum alone is NOT the verdict
 *
 * The mirror does not describe a PRE-BLOCK receive at all — not its
 * finality and not its account. Both are written only when the transaction
 * is mined, so for the whole mempool window the eligibility sum over a
 * freshly funded wallet is 0 no matter how much the wallet holds and no
 * matter that the engine has already IS-locked it. Reading that 0 as a
 * shortfall refused username creation, invitations and top-ups on every
 * fresh wallet for a full block (S21 mainnet, 11.10.67 — see
 * [UNCLASSIFIED_ASSET_LOCK_DUFFS_SQL] for the timestamped trace).
 *
 * So the preflight reports EVIDENCE, not a number: the eligible sum
 * together with the unspent value the mirror could not classify. Only when
 * the classified part rules the funding out is there a shortfall to gate
 * on — [assetLockFundingVerdict].
 *
 * ## Fail-open contract
 *
 * READ-ONLY and advisory: never broadcasts, never reserves, never touches
 * engine state. Every unexpected condition (cutover not committed, SDK not
 * running, DB missing, query failure) returns `null` = "no evidence either
 * way" and callers must treat it as fundable (fail open) — the flow must
 * never be blocked on an unrelated hiccup; the real build remains the
 * authority. Only a successfully-computed shortfall gates.
 */
@Singleton
class SdkAssetLockFundingPreflight internal constructor(
    /**
     * True once the cutover is COMMITTED — the only regime in which SDK
     * asset-lock funding is routed to at all. Pre-cutover the dashj path
     * owns funding (different selection rules, and the SDK mirror may be
     * empty), so the preflight reports not-applicable (`null`).
     */
    private val cutoverCommitted: suspend () -> Boolean,
    /**
     * The mirror's [AssetLockFundingEvidence] for the bound wallet, or
     * `null` when unavailable. Production wiring runs the two SQL passes
     * against the SDK's Room database.
     */
    private val evidenceQuery: suspend () -> AssetLockFundingEvidence?,
    /**
     * COUNT twin of [evidenceQuery]'s eligible sum: the eligible-UTXO
     * population, for sizing a MAX top-up's fee reserve. `null` when
     * unavailable.
     */
    private val eligibleUtxoCountQuery: suspend () -> Int? = { null }
) {
    @Inject
    constructor(
        sdkL1SendService: SdkL1SendService,
        sdkService: DashSdkService,
        instantSendLockDao: de.schildbach.wallet.database.dao.InstantSendLockDao
    ) : this(
        cutoverCommitted = { sdkL1SendService.cutoverCommitted() },
        evidenceQuery = {
            queryAssetLockFundingEvidence(
                sdkService.databaseOrNull(),
                sdkService.walletManagerOrNull()?.wallets?.value?.keys?.singleOrNull(),
                // The app's own persisted IS-lock evidence (CutoverUiDataService
                // writes it the moment the engine reports a lock) — folded into
                // the finality term so a pre-block IS-locked receive counts as
                // FINAL instead of failing open. Fail-soft: a failed read just
                // means no locks are joined this evaluation (the pre-fix
                // behaviour), never a blocked flow.
                persistedLockTxidsHex = try {
                    instantSendLockDao.getMostRecentTxIds(MAX_PREFLIGHT_LOCK_TXIDS)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.warn("persisted IS-lock read failed; preflight evaluates without lock evidence", t)
                    emptyList()
                }
            )
        },
        eligibleUtxoCountQuery = {
            queryEligibleAssetLockUtxoCount(
                sdkService.databaseOrNull(),
                sdkService.walletManagerOrNull()?.wallets?.value?.keys?.singleOrNull(),
                // Same lock evidence as evidenceQuery, so the count's
                // population cannot diverge from the sum's.
                persistedLockTxidsHex = try {
                    instantSendLockDao.getMostRecentTxIds(MAX_PREFLIGHT_LOCK_TXIDS)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.warn("persisted IS-lock read failed; UTXO count evaluates without lock evidence", t)
                    emptyList()
                }
            )
        }
    )

    /**
     * What the mirror can say about asset-lock fundability, or `null` when
     * the preflight does not apply (pre-cutover) or has no evidence
     * (SDK/DB unavailable, read failure) — callers MUST fail open on
     * `null`. Never throws.
     */
    suspend fun assetLockFundingEvidenceOrNull(): AssetLockFundingEvidence? {
        val committed = try {
            cutoverCommitted()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("asset-lock funding preflight: cutover state read failed; fail open", t)
            return null
        }
        if (!committed) return null
        return try {
            evidenceQuery()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("asset-lock funding preflight: eligibility read failed; fail open", t)
            null
        }
    }

    /**
     * Can a fresh asset lock of [requiredDuffs] (+ fee headroom) be funded?
     * `null` = no evidence either way — treat as fundable (fail open).
     * A `false` is logged with the figures for on-device forensics.
     */
    /**
     * The number of UTXOs a fresh asset-lock build can select — the input
     * population whose per-input bytes dominate the L1 fee. `null` = no
     * evidence (pre-cutover, SDK unavailable, read failure); callers fall
     * back to not adjusting rather than guessing.
     */
    suspend fun eligibleAssetLockUtxoCountOrNull(): Int? {
        val committed = try {
            cutoverCommitted()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("asset-lock funding preflight: cutover state read failed; no UTXO count", t)
            return null
        }
        if (!committed) return null
        return try {
            eligibleUtxoCountQuery()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("asset-lock funding preflight: UTXO count read failed", t)
            null
        }
    }

    suspend fun canFundAssetLockDuffs(requiredDuffs: Long): Boolean? {
        val evidence = assetLockFundingEvidenceOrNull() ?: return null
        val verdict = assetLockFundingVerdict(evidence, requiredDuffs)
        if (verdict == false) {
            log.info(
                "asset-lock funding preflight FAILED: eligible {} duffs on BIP44 account 0 " +
                    "(final: confirmed/IS-locked, unlocked, mature) plus {} unclassified duffs " +
                    "cannot cover {} + {} duffs headroom",
                evidence.eligibleDuffs,
                evidence.unclassifiedDuffs,
                requiredDuffs,
                ASSET_LOCK_PREFLIGHT_FEE_HEADROOM_DUFFS
            )
        }
        return verdict
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkAssetLockFundingPreflight::class.java)

        /**
         * Cap on persisted IS-lock txids bound into one query — the lock
         * blobs share SQLite's 999-variable budget with the walletId.
         * Locks only matter for the pre-block window, so even a burst of
         * receives sits far under this; anything truncated merely falls to
         * the unclassified bucket (fail open), never a false shortfall.
         */
        internal const val MAX_PREFLIGHT_LOCK_TXIDS = 500

        /**
         * Both read-only SQL passes over the SDK's Room mirror (same
         * `openHelper` raw-query precedent as [CutoverUiDataService]'s
         * unspent-sum probe), in ONE IO hop. Returns `null` when the DB or
         * the bound wallet id is unavailable.
         *
         * Coinbase maturity: phone wallets essentially never hold coinbase
         * outputs; rather than plumb a chain-tip read for the `height+100`
         * rule, coinbase rows are excluded outright (conservative — can
         * only under-count, never over-count).
         */
        /**
         * COUNT twin of [queryAssetLockFundingEvidence]'s eligible sum — how
         * many UTXOs the asset-lock coin selection can draw on, counted over
         * the SAME spine and the SAME persisted-IS-lock finality evidence so
         * the population cannot diverge from the sum's. `null` when the SDK
         * database or wallet binding is unavailable.
         */
        internal suspend fun queryEligibleAssetLockUtxoCount(
            database: org.dashfoundation.dashsdk.persistence.DashDatabase?,
            walletIdHex: String?,
            persistedLockTxidsHex: List<String> = emptyList()
        ): Int? {
            val db = database ?: return null
            val walletId = walletIdHex?.let { walletIdFromHex(it) } ?: return null
            val lockBlobs = persistedLockTxidsHex
                .take(MAX_PREFLIGHT_LOCK_TXIDS)
                .mapNotNull { hexToBytesOrNull(it.lowercase())?.takeIf { b -> b.size == 32 }?.reversedArray() }
            return withContext(Dispatchers.IO) {
                val args = ArrayList<Any?>(1 + lockBlobs.size)
                args.add(walletId)
                args.addAll(lockBlobs)
                db.openHelper.readableDatabase.query(
                    androidx.sqlite.db.SimpleSQLiteQuery(
                        eligibleAssetLockUtxoCountSql(lockBlobs.size),
                        args.toTypedArray()
                    )
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            }
        }

        internal suspend fun queryAssetLockFundingEvidence(
            database: org.dashfoundation.dashsdk.persistence.DashDatabase?,
            walletIdHex: String?,
            /**
             * Display-hex txids with an APP-PERSISTED IS lock
             * ([de.schildbach.wallet.database.dao.InstantSendLockDao]) —
             * joined into the finality term as wire-order blobs so IS-locked
             * coins count as final DURING the pre-block window the mirror
             * cannot describe. Bounded by [MAX_PREFLIGHT_LOCK_TXIDS];
             * over-supply is truncated (omitted locks merely fall back to
             * unclassified = fail open, never a false shortfall).
             */
            persistedLockTxidsHex: List<String> = emptyList()
        ): AssetLockFundingEvidence? {
            val db = database ?: return null
            val walletId = walletIdHex?.let { walletIdFromHex(it) } ?: return null
            // Display-order hex → the wire-order 32-byte blobs `txos.txid` stores
            // (same convention as CutoverUiSource.coinJoinFundedTxids). Malformed
            // entries are dropped rather than failing the whole preflight.
            val lockBlobs = persistedLockTxidsHex
                .take(MAX_PREFLIGHT_LOCK_TXIDS)
                .mapNotNull { hexToBytesOrNull(it.lowercase())?.takeIf { b -> b.size == 32 }?.reversedArray() }
            return withContext(Dispatchers.IO) {
                AssetLockFundingEvidence(
                    eligibleDuffs = sumDuffs(db, eligibleAssetLockDuffsSql(lockBlobs.size), walletId, lockBlobs),
                    unclassifiedDuffs = sumDuffs(db, unclassifiedAssetLockDuffsSql(lockBlobs.size), walletId, lockBlobs)
                )
            }
        }

        private fun sumDuffs(
            db: org.dashfoundation.dashsdk.persistence.DashDatabase,
            sql: String,
            walletId: ByteArray,
            lockBlobs: List<ByteArray> = emptyList()
        ): Long {
            val args = ArrayList<Any?>(1 + lockBlobs.size)
            args.add(walletId)
            args.addAll(lockBlobs)
            return db.openHelper.readableDatabase.query(
                // See the class KDoc for the source-level trace of every term,
                // and the constants' KDoc for the dead-`isInstantLocked`,
                // NULL-`accountId` and pre-block mirror realities they
                // compensate for.
                androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        }
    }
}
