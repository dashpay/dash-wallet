/*
 * Copyright 2024 Dash Core Group.
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

package de.schildbach.wallet.ui.payments

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.payments.RequestWalletBalanceTask
import de.schildbach.wallet.service.platform.sdk.CutoverCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitcoinj.core.Transaction
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcasts a signed paper-wallet sweep transaction to the Electrum /
 * block-explorer infrastructure when the SDK cutover has committed and the
 * dashj peergroup is held.
 *
 * Pre-cutover this is a deliberate no-op: the dashj peergroup still broadcasts
 * via `WalletApplication.processDirectTransaction`, so the existing path is
 * left completely unchanged. Post-cutover that peergroup broadcast no-ops
 * ("peergroup not available, not broadcasting"), so the signed transaction is
 * pushed to the SAME Electrum servers + block explorers the sweep already used
 * to discover the swept UTXOs ([RequestWalletBalanceTask]).
 *
 * The swept funds land on the user's own fresh receive address, so the SDK L1
 * engine detects the incoming transaction on its own scan — nothing needs to be
 * injected back into dashj here.
 */
@Singleton
class SweepTxBroadcaster @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cutoverCoordinator: CutoverCoordinator,
    private val applicationScope: CoroutineScope
) {
    companion object {
        private val log = LoggerFactory.getLogger(SweepTxBroadcaster::class.java)
    }

    /**
     * Fire-and-forget: matches today's sweep broadcast semantics (the dashj
     * peergroup path is also fire-and-forget). Only broadcasts via Electrum
     * when the cutover is committed (dashj L1 engine held); otherwise returns
     * immediately and lets the still-live peergroup handle it.
     */
    fun broadcastIfCutoverCommitted(tx: Transaction) {
        applicationScope.launch(Dispatchers.IO) {
            val cutoverCommitted = try {
                !cutoverCoordinator.dashjEngineMayStart()
            } catch (e: Exception) {
                log.warn("could not read cutover state; leaving the sweep broadcast to the dashj peergroup", e)
                false
            }

            if (!cutoverCommitted) {
                // Pre-cutover: the dashj peergroup broadcasts via
                // processDirectTransaction — nothing to do here.
                return@launch
            }

            try {
                val rawTxHex = Constants.HEX.encode(tx.bitcoinSerialize())
                val txid = RequestWalletBalanceTask().broadcastTransactionBlocking(context.assets, rawTxHex)
                log.info("cutover committed — swept transaction broadcast via electrum/explorer, txid {}", txid)
            } catch (e: Exception) {
                log.error("cutover committed — failed to broadcast swept transaction via electrum/explorer", e)
            }
        }
    }
}
