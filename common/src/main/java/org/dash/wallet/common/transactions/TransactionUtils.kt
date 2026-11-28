/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.transactions

object TransactionUtils {
    /**
     * The wallet address the transaction was received to: the destination of the first
     * output that pays the wallet AND has a resolvable address (base58), or null. Mine
     * outputs whose address can't be resolved are skipped, like the old dashj code did.
     *
     * Delta vs the dashj original: P2PK outputs resolve to a null address in the neutral
     * model (full P2PK address derivation was intentionally not ported), so they are
     * skipped here where the old code could derive an address from the pubkey.
     */
    fun getWalletAddressOfReceived(tx: TxInfo): String? {
        return tx.outputs.firstOrNull { it.isMine && it.address != null }?.address
    }
}
