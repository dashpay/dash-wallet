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

package de.schildbach.wallet.data

import de.schildbach.wallet.transactions.LockedTransaction
import de.schildbach.wallet.transactions.NeutralFilterAdapter
import de.schildbach.wallet.transactions.WalletTransactionFilter
import de.schildbach.wallet.transactions.toTxInfo
import de.schildbach.wallet.transactions.waitToMatchFilters
import de.schildbach.wallet.util.toCoin
import de.schildbach.wallet.util.toDash
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.bitcoinj.core.Address
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.TransactionFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements the neutral [WalletDataProvider] facade for feature/integration modules on top of
 * the wallet module's dashj-typed [WalletData]. All dashj/neutral conversions are concentrated
 * here; behavior mirrors the previous dashj-typed facade exactly.
 */
@Singleton
class WalletDataAdapter @Inject constructor(
    private val walletData: WalletData
) : WalletDataProvider {

    override val networkId: String
        get() = walletData.networkParameters.id

    @Suppress("DEPRECATION")
    override val walletLoaded: Boolean
        get() = walletData.wallet != null

    override fun freshReceiveAddressString(): String = walletData.freshReceiveAddress().toBase58()

    override fun currentReceiveAddressString(): String = walletData.currentReceiveAddress().toBase58()

    override fun getWalletBalance(): Dash = walletData.getWalletBalance().toDash()

    override fun spendableUtxoCount(): Int = walletData.spendableUtxoCount()

    override fun observeWalletChanged(): Flow<Unit> = walletData.observeWalletChanged()

    override fun observeWalletReset(): Flow<Unit> = walletData.observeWalletReset()

    override fun observeEstimatedBalance(): Flow<Dash> = walletData.observeBalance().map { it.toDash() }

    override fun canAffordIdentityCreation(): Boolean = walletData.canAffordIdentityCreation()

    override fun observeTransactions(withConfidence: Boolean, vararg filters: TransactionFilter): Flow<TxInfo> {
        // The filters must go INTO the observer (not be applied post-hoc): there they gate both
        // emission and confidence-listener registration, exactly like the old dashj-typed path.
        val dashjFilters = filters.map {
            NeutralFilterAdapter(it, walletData.transactionBag, walletData.networkParameters)
        }.toTypedArray<WalletTransactionFilter>()
        return walletData.observeTransactions(withConfidence, *dashjFilters)
            .map { it.toTxInfo(walletData.transactionBag, walletData.networkParameters) }
    }

    override fun getTransaction(txId: String): TxInfo? {
        return walletData.getTransaction(Sha256Hash.wrap(txId))
            ?.toTxInfo(walletData.transactionBag, walletData.networkParameters)
    }

    override fun getTransactions(vararg filters: TransactionFilter): Collection<TxInfo> {
        return walletData.getTransactions()
            .map { it.toTxInfo(walletData.transactionBag, walletData.networkParameters) }
            .filter { tx -> filters.isEmpty() || filters.any { it.matches(tx) } }
    }

    override fun wrapAllTransactions(vararg wrappers: TransactionWrapperFactory): Collection<TransactionWrapper> {
        return walletData.wrapAllTransactions(*wrappers)
    }

    override fun attachOnWalletWipedListener(listener: suspend () -> Unit) =
        walletData.attachOnWalletWipedListener(listener)

    override fun detachOnWalletWipedListener(listener: suspend () -> Unit) =
        walletData.detachOnWalletWipedListener(listener)

    override fun checkSendingConditions(address: String?, amount: Dash) {
        walletData.checkSendingConditions(
            address?.let { Address.fromBase58(walletData.networkParameters, it) },
            amount.toCoin()
        )
    }

    override fun observeTotalBalance(): Flow<Dash> = walletData.observeTotalBalance().map { it.toDash() }

    override fun lockOutputsPayingTo(txId: String, address: String) {
        // Fail closed: callers just sent this tx from this wallet, so a miss is an invariant
        // violation — silently skipping would leave CrowdNode signup funds unlocked.
        val tx = walletData.getTransaction(Sha256Hash.wrap(txId))
            ?: throw IllegalStateException("transaction $txId not found in wallet")
        val params = walletData.networkParameters
        val target = Address.fromBase58(params, address)
        tx.outputs.filter { output ->
            ScriptPattern.isP2PKH(output.scriptPubKey) &&
                Address.fromPubKeyHash(params, ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)) == target
        }.forEach { output ->
            walletData.lockOutput(output.outPointFor)
        }
    }

    override suspend fun waitUntilLocked(txId: String) {
        // Fail closed rather than pretending the tx is locked (see lockOutputsPayingTo).
        val tx = walletData.getTransaction(Sha256Hash.wrap(txId))
            ?: throw IllegalStateException("transaction $txId not found in wallet")
        tx.waitToMatchFilters(LockedTransaction())
    }
}
