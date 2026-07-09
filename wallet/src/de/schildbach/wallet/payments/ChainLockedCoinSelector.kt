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

package de.schildbach.wallet.payments

import org.bitcoinj.core.Coin
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.wallet.CoinSelection
import org.bitcoinj.wallet.CoinSelector

/**
 * Selects ONLY outputs whose containing transaction is ChainLocked — used
 * by the shielded internal-transfer screen so the "From: Dash Wallet"
 * balance (and the transferable/Max amount) counts nothing that a chain
 * reorganization could still take back.
 *
 * An output is treated as chainlocked when its parent transaction is in
 * the best chain (`BUILDING`) and any of:
 *
 * 1. dashj marked the transaction's confidence chainlocked
 *    ([TransactionConfidence.isChainLocked] — set live by the
 *    `ChainLocksHandler` as chainlock signatures arrive), or
 * 2. its `appearedAtChainHeight` is at or below [chainLockHeight] — the
 *    persisted `BlockchainState.chainlockHeight`, written by
 *    `BlockchainStateDataProvider.updateBlockchainState` from
 *    `chainLockHandler.bestChainLockBlockHeight`. A chainlock at height H
 *    locks the whole chain up to H, so every transaction in a block at or
 *    below H is chainlocked.
 *
 * ## Fallback decision (documented per AC4)
 *
 * Testnet and mainnet both produce chainlocks and both sources above
 * populate (verified: `BlockchainStateDataProvider` persists
 * `chainlockHeight`/`mnlistHeight` on every sync-progress update, and the
 * app's transaction UI already relies on `confidence.isChainLocked` on
 * all networks). However `BlockchainState.chainlockHeight` is only
 * refreshed during sync-progress callbacks (see the TODO in
 * `BlockchainState`), so it can lag or read 0 right after a fresh install
 * before the first chainlock is processed. Treating "no chainlock info"
 * as "nothing is chainlocked" would zero the user's balance and brick the
 * feature whenever chainlocks lag — instead, when [chainLockHeight] is
 * 0/unavailable the selector falls back to a conservative depth check:
 * outputs with at least [FALLBACK_CONFIRMATIONS] confirmations relative
 * to [bestChainHeight] (the app's existing "no longer confirming" idiom,
 * see `TxResourceMapper`) count as effectively irreversible. When both
 * heights are unavailable (no persisted blockchain state at all) nothing
 * is selected — with no state the chain is not synced and transfers are
 * gated off anyway.
 */
class ChainLockedCoinSelector(
    private val chainLockHeight: Int,
    private val bestChainHeight: Int
) : CoinSelector {

    override fun select(target: Coin, candidates: MutableList<TransactionOutput>): CoinSelection {
        val selected = candidates.filter { output ->
            val confidence = output.parentTransaction?.confidence
            confidence != null &&
                confidence.confidenceType == TransactionConfidence.ConfidenceType.BUILDING &&
                (
                    confidence.isChainLocked ||
                        isChainLockedHeight(
                            confidence.appearedAtChainHeight,
                            chainLockHeight,
                            bestChainHeight
                        )
                    )
        }
        val value = Coin.valueOf(selected.sumOf { it.value.value })
        return CoinSelection(value, selected)
    }

    companion object {
        /**
         * Depth treated as irreversible when no chainlock height is
         * available — matches the app's 6-confirmation "confirmed" idiom.
         */
        const val FALLBACK_CONFIRMATIONS = 6

        /**
         * Pure height math (host-testable): whether a transaction that
         * appeared at [appearedAtHeight] is chainlocked given the best
         * known [chainLockHeight], falling back to a
         * [FALLBACK_CONFIRMATIONS]-deep check against [bestChainHeight]
         * when the chainlock height is 0/unavailable.
         */
        fun isChainLockedHeight(appearedAtHeight: Int, chainLockHeight: Int, bestChainHeight: Int): Boolean =
            when {
                appearedAtHeight <= 0 -> false
                chainLockHeight > 0 -> appearedAtHeight <= chainLockHeight
                bestChainHeight > 0 -> bestChainHeight - appearedAtHeight + 1 >= FALLBACK_CONFIRMATIONS
                else -> false
            }
    }
}
