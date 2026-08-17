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

import de.schildbach.wallet.service.platform.sdk.CutoverTxSeamService
import de.schildbach.wallet.service.platform.sdk.SeamOutputLockRegistry
import de.schildbach.wallet.transactions.LockedTransaction
import de.schildbach.wallet.transactions.NeutralFilterAdapter
import de.schildbach.wallet.transactions.WalletTransactionFilter
import de.schildbach.wallet.transactions.toTxInfo
import de.schildbach.wallet.transactions.waitToMatchFilters
import de.schildbach.wallet.util.toCoin
import de.schildbach.wallet.util.toDash
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.bitcoinj.core.Address
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.LockedTransaction as NeutralLockedTransaction
import org.dash.wallet.common.transactions.filters.TransactionFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements the neutral [WalletDataProvider] facade for feature/integration modules on top of
 * the wallet module's dashj-typed [WalletData]. All dashj/neutral conversions are concentrated
 * here; behavior mirrors the previous dashj-typed facade exactly.
 *
 * ## Post-cutover transaction reads
 *
 * Once the cutover is committed the dashj wallet is held/frozen — externally-received
 * transactions never reach it — so [observeTransactions], [getTransaction] and
 * [getTransactions] switch to the SDK-fed [CutoverTxSeamService]. The switch lives HERE, at
 * the seam: every feature/integration consumer (CrowdNode's signup/deposit state machine
 * first among them) inherits live data with zero feature-code changes. Routing is per-call/
 * reactive: pre-cutover (and after a rollback, and whenever the SDK feed is unavailable)
 * everything takes the unchanged dashj path.
 */
@Singleton
class WalletDataAdapter @Inject constructor(
    private val walletData: WalletData,
    // dagger.Lazy breaks the DI cycle DashPayConfig → WalletDataProvider →
    // WalletDataAdapter → CutoverTxSeamService → DashPayConfig; the seam
    // service is only dereferenced at call time, never at construction.
    private val txSeamServiceLazy: dagger.Lazy<CutoverTxSeamService>,
    private val seamLockRegistry: SeamOutputLockRegistry
) : WalletDataProvider {

    private val txSeamService: CutoverTxSeamService
        get() = txSeamServiceLazy.get()

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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTransactions(withConfidence: Boolean, vararg filters: TransactionFilter): Flow<TxInfo> {
        // Reactive cutover routing: activeState is synchronously false pre-cutover (the dashj
        // observer attaches immediately, byte-identical to before) and flips per DataStore
        // emission, so a rollback mid-observation switches live subscriptions back to dashj.
        return txSeamService.activeState.flatMapLatest { sdkFed ->
            if (sdkFed) {
                txSeamService.observeSdkTransactions(withConfidence, filters)
            } else {
                observeDashjTransactions(withConfidence, filters)
            }
        }
    }

    private fun observeDashjTransactions(withConfidence: Boolean, filters: Array<out TransactionFilter>): Flow<TxInfo> {
        // The filters must go INTO the observer (not be applied post-hoc): there they gate both
        // emission and confidence-listener registration, exactly like the old dashj-typed path.
        val dashjFilters = filters.map {
            NeutralFilterAdapter(it, walletData.transactionBag, walletData.networkParameters)
        }.toTypedArray<WalletTransactionFilter>()
        return walletData.observeTransactions(withConfidence, *dashjFilters)
            .map { it.toTxInfo(walletData.transactionBag, walletData.networkParameters) }
    }

    override fun getTransaction(txId: String): TxInfo? {
        // Post-cutover the SDK snapshot wins (live lock state; the held dashj wallet's is
        // frozen); the dashj lookup remains as the fallback for anything the SDK store does
        // not have — stale-but-real, never fabricated.
        txSeamService.sdkTxInfosOrNull()?.get(txId.lowercase())?.let { return it }
        return walletData.getTransaction(Sha256Hash.wrap(txId))
            ?.toTxInfo(walletData.transactionBag, walletData.networkParameters)
    }

    override fun getTransactions(vararg filters: TransactionFilter): Collection<TxInfo> {
        val sdkTxs = txSeamService.sdkTxInfosOrNull()
        val all: Collection<TxInfo> = if (sdkTxs != null) {
            // Post-cutover: the SDK-fed set, plus (dedup'd by txid) any held-dashj-wallet
            // transactions the SDK store never learned — history must never shrink across
            // the cutover. The SDK map is a LAZY store-backed view, so enumerate it ONCE
            // and dedup against a local id set instead of a per-dashj-tx containsKey probe.
            val sdkValues = sdkTxs.values.toList()
            val sdkIds = sdkValues.mapTo(HashSet()) { it.txId }
            val dashjOnly = walletData.getTransactions()
                .filter { it.txId.toString() !in sdkIds }
                .map { it.toTxInfo(walletData.transactionBag, walletData.networkParameters) }
            sdkValues + dashjOnly
        } else {
            walletData.getTransactions()
                .map { it.toTxInfo(walletData.transactionBag, walletData.networkParameters) }
        }
        return all.filter { tx -> filters.isEmpty() || filters.any { it.matches(tx) } }
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
        // Held-wallet path first: self-authored txs live in the dashj wallet even
        // post-cutover, and pre-cutover this is the only path (byte-identical to before).
        val tx = walletData.getTransaction(Sha256Hash.wrap(txId))
        if (tx != null) {
            val params = walletData.networkParameters
            val target = Address.fromBase58(params, address)
            tx.outputs.filter { output ->
                ScriptPattern.isP2PKH(output.scriptPubKey) &&
                    Address.fromPubKeyHash(params, ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)) == target
            }.forEach { output ->
                walletData.lockOutput(output.outPointFor)
            }
            return
        }
        // Post-cutover: an INCOMING SDK-fed tx (e.g. the CrowdNode signup response the
        // caller just observed through the seam) never reaches the held dashj wallet —
        // register its matching outputs in the seam lock registry instead. Address
        // matching is by standard-address string equality, which for a P2PKH target is
        // exactly the old ScriptPattern.isP2PKH + pubkey-hash-address check (a P2SH
        // output's base58 can never equal a P2PKH target's).
        val seamTx = txSeamService.sdkTxInfosOrNull()?.get(txId.lowercase())
        if (seamTx != null) {
            seamTx.outputs
                .filter { !it.isOpReturn && it.address == address }
                .forEach { output -> seamLockRegistry.lockOutput(seamTx.txId, output.index) }
            return
        }
        // Fail closed only when BOTH sources miss: callers just sent/received this tx,
        // so a total miss is an invariant violation — silently skipping would leave
        // CrowdNode signup funds unlocked.
        throw IllegalStateException("transaction $txId not found in wallet")
    }

    override suspend fun waitUntilLocked(txId: String) {
        // Post-cutover seam path FIRST: a bridged self-authored tx (an SDK send) also
        // exists in the held dashj wallet, but its confidence is FROZEN there — no
        // peergroup ever delivers it an IS-lock — so the held-wallet wait would sit
        // out any timeout even though the engine saw the lock within seconds (the
        // 2026-08-05 Maya mainnet field test: engine IS-lock in 1.6s, UI waited the
        // full 10s). The seam's TxInfo carries the live engine lock state, and the
        // current-state replay means an already-locked tx returns immediately.
        if (txSeamService.sdkTxInfosOrNull()?.get(txId.lowercase()) != null) {
            txSeamService
                .observeSdkTransactionsWithCurrentState(arrayOf(NeutralLockedTransaction(txId)))
                .first()
            return
        }
        // Pre-cutover (and anything the SDK store never learned): dashj confidence
        // semantics, byte-identical to before.
        val tx = walletData.getTransaction(Sha256Hash.wrap(txId))
        if (tx != null) {
            tx.waitToMatchFilters(LockedTransaction())
            return
        }
        // Fail closed rather than pretending the tx is locked (see lockOutputsPayingTo):
        // the tx exists nowhere.
        throw IllegalStateException("transaction $txId not found in wallet")
    }
}
