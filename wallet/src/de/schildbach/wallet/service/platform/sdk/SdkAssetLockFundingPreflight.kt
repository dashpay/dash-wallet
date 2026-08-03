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
 * as [RECEIVAL_FALLBACK_FEE_HEADROOM_DUFFS]: at the asset-lock builder's
 * default rate (`DEFAULT_FEE_PER_KB` = 1000 duffs/kB) the fee is
 * `~44 + 148·n_inputs` duffs, so 10 000 covers ~67 inputs — far beyond a
 * typical wallet. A wallet that exceeds it anyway fails the REAL build
 * pre-broadcast exactly as before; the preflight is advisory-conservative,
 * never a funds-safety boundary.
 */
const val ASSET_LOCK_PREFLIGHT_FEE_HEADROOM_DUFFS = 10_000L

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
 * `!isLocked && (isConfirmed || isInstantLocked) && coinbase-mature`
 * — mirroring `require_final_inputs` + `Utxo::is_spendable` exactly.
 * Account scoping routes `txos.address → core_addresses.accountId →
 * accounts` (the same join the SDK's own `buildUtxoRestoreData` uses,
 * because `txos.accountId` is not populated on Android), restricted to
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
     * The asset-lock-ELIGIBLE duffs on BIP44 account 0 (see class KDoc for
     * the predicate), or `null` when unavailable. Production wiring runs
     * the SQL against the SDK's Room database.
     */
    private val eligibleDuffsQuery: suspend () -> Long?
) {
    @Inject
    constructor(
        sdkL1SendService: SdkL1SendService,
        sdkService: DashSdkService
    ) : this(
        cutoverCommitted = { sdkL1SendService.cutoverCommitted() },
        eligibleDuffsQuery = {
            queryEligibleAssetLockDuffs(
                sdkService.databaseOrNull(),
                sdkService.walletManagerOrNull()?.wallets?.value?.keys?.singleOrNull()
            )
        }
    )

    /**
     * The asset-lock-eligible duffs, or `null` when the preflight does not
     * apply (pre-cutover) or has no evidence (SDK/DB unavailable, read
     * failure) — callers MUST fail open on `null`. Never throws.
     */
    suspend fun eligibleAssetLockFundingDuffsOrNull(): Long? {
        val committed = try {
            cutoverCommitted()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("asset-lock funding preflight: cutover state read failed; fail open", t)
            return null
        }
        if (!committed) return null
        return try {
            eligibleDuffsQuery()
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
    suspend fun canFundAssetLockDuffs(requiredDuffs: Long): Boolean? {
        val eligible = eligibleAssetLockFundingDuffsOrNull() ?: return null
        val covers = assetLockFundingCovers(eligible, requiredDuffs)
        if (!covers) {
            log.info(
                "asset-lock funding preflight FAILED: eligible {} duffs on BIP44 account 0 " +
                    "(final: confirmed/IS-locked, unlocked, mature) cannot cover {} + {} duffs headroom",
                eligible,
                requiredDuffs,
                ASSET_LOCK_PREFLIGHT_FEE_HEADROOM_DUFFS
            )
        }
        return covers
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkAssetLockFundingPreflight::class.java)

        /**
         * One read-only SQL pass over the SDK's Room mirror (same
         * `openHelper` raw-query precedent as
         * [CutoverUiDataService]'s unspent-sum probe). Returns `null` when
         * the DB or the bound wallet id is unavailable. INNER joins drop
         * rows with no resolvable account — the SDK's own restore builder
         * skips exactly those rows ("a UTXO the wallet can't attribute
         * can't be routed"), so the engine never funds from them either.
         *
         * Coinbase maturity: phone wallets essentially never hold coinbase
         * outputs; rather than plumb a chain-tip read for the `height+100`
         * rule, coinbase rows are excluded outright (conservative — can
         * only under-count, never over-count).
         */
        internal suspend fun queryEligibleAssetLockDuffs(
            database: org.dashfoundation.dashsdk.persistence.DashDatabase?,
            walletIdHex: String?
        ): Long? {
            val db = database ?: return null
            val walletId = walletIdHex?.let { walletIdFromHex(it) } ?: return null
            return withContext(Dispatchers.IO) {
                db.openHelper.readableDatabase.query(
                    androidx.sqlite.db.SimpleSQLiteQuery(
                        // The asset-lock eligibility predicate — see the class
                        // KDoc for the source-level trace of every term.
                        "SELECT COALESCE(SUM(t.amount), 0) FROM txos t " +
                            "JOIN core_addresses ca ON ca.address = t.address " +
                            "JOIN accounts a ON a.id = ca.accountId " +
                            "WHERE t.walletId = ? " +
                            "AND t.isSpent = 0 AND t.spendingTxid IS NULL " +
                            "AND t.isLocked = 0 AND t.isCoinbase = 0 " +
                            "AND (t.isConfirmed = 1 OR t.isInstantLocked = 1) " +
                            "AND a.accountType = 0 AND a.standardTag = 0 AND a.accountIndex = 0",
                        arrayOf<Any?>(walletId)
                    )
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            }
        }
    }
}
