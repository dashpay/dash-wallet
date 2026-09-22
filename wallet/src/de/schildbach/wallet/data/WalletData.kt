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

import de.schildbach.wallet.payments.MaxOutputAmountCoinSelector
import de.schildbach.wallet.transactions.WalletTransactionFilter
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.wallet.CoinSelector
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory

/**
 * Dashj-typed wallet facade for wallet-module consumers. This is the former (pre-neutralization)
 * `org.dash.wallet.common.WalletDataProvider` surface, unchanged; feature/integration modules use
 * the neutral `WalletDataProvider` instead, which `WalletDataAdapter` implements on top of this.
 */
interface WalletData {
    @Deprecated("The wallet is in here temporary and will be moved to a separate holder, limited to the the wallet module.")
    val wallet: Wallet?

    fun observeWallet(): Flow<Wallet?>

    val transactionBag: TransactionBag

    val networkParameters: NetworkParameters
    val authenticationGroupExtension: AuthenticationGroupExtension?
    fun freshReceiveAddress(): Address
    fun currentReceiveAddress(): Address

    /**
     * [currentReceiveAddress] / [freshReceiveAddress] answered by a LIVE read of
     * whichever engine actually owns the key chain right now, instead of a
     * cached one.
     *
     * Post-cutover that is the Kotlin SDK: the dashj wallet is HELD, so its
     * receive pointer is frozen wherever the restore left it (index 0 on a fresh
     * restore) and both plain methods above would serve an address the chain has
     * ALREADY paid — SR-03 / D-003, the Receive screen re-serving the funded
     * address after Reset Wallet + restore. [de.schildbach.wallet.WalletApplication]
     * overrides these to read the engine's next UNUSED address over the SDK FFI
     * (`core_wallet_next_receive_address`), NOT the SDK's Room address mirror,
     * which lags the engine's used-set by a persistence pass.
     *
     * Pre-cutover — and in this default — they are the dashj answers unchanged,
     * so `fresh` keeps its per-invoice handout there.
     *
     * BLOCKING: post-cutover these take the engine's wallet-manager lock (and
     * pre-cutover `fresh` still forces dashj's synchronous full-wallet save).
     * Call them off the main thread — [freshReceiveAddressOffMain] and
     * [de.schildbach.wallet.ui.payments.PaymentsViewModel] already impose that.
     */
    fun currentReceiveAddressLive(): Address = currentReceiveAddress()
    fun freshReceiveAddressLive(): Address = freshReceiveAddress()

    val networkId: String
        get() = networkParameters.id
    fun freshReceiveAddressString(): String = freshReceiveAddress().toBase58()
    fun currentReceiveAddressString(): String = currentReceiveAddress().toBase58()

    fun getWalletBalance(): Coin

    /**
     * Number of spendable unspent outputs coin selection can draw on —
     * `calculateAllSpendCandidates(false, false)`, the exact output set
     * `getBalance(ESTIMATED)` sums (all keychains) — or 0 while no wallet
     * is loaded.
     */
    @Suppress("DEPRECATION")
    fun spendableUtxoCount(): Int = wallet?.calculateAllSpendCandidates(false, false)?.size ?: 0

    fun observeWalletChanged(): Flow<Unit>

    fun observeWalletReset(): Flow<Unit>

    fun observeBalance(
        balanceType: Wallet.BalanceType = Wallet.BalanceType.ESTIMATED,
        coinSelector: CoinSelector? = null
    ): Flow<Coin>

    /**
     * The send screen's "max sendable" DISPLAY feed: the dashj max-output-coin-selector
     * balance (ESTIMATED total minus the fee to spend it all). [de.schildbach.wallet.WalletApplication]
     * overrides this to overlay the SDK's live total post-cutover (the plain
     * [observeBalance] overlay skips selector-based streams by design); pre-cutover — and
     * for this default fallback — it is the dashj value unchanged. This feed drives only
     * the send screen's displayed available balance / max-amount cap; the real send's coin
     * selection is owned independently by [de.schildbach.wallet.payments.SendCoinsTaskRunner].
     */
    fun observeMaxOutputBalance(): Flow<Coin> =
        observeBalance(Wallet.BalanceType.ESTIMATED, MaxOutputAmountCoinSelector())

    fun canAffordIdentityCreation(): Boolean

    // Treat @withConfidence with care - it may produce a lot of events and affect performance.
    fun observeTransactions(withConfidence: Boolean = false, vararg filters: WalletTransactionFilter): Flow<Transaction>

    fun observeAuthenticationKeyUsage(): Flow<List<AuthenticationKeyUsage>>

    fun getTransaction(hash: Sha256Hash): Transaction?

    fun getTransactions(vararg filters: WalletTransactionFilter): Collection<Transaction>

    fun wrapAllTransactions(vararg wrappers: TransactionWrapperFactory): Collection<TransactionWrapper>

    fun attachOnWalletWipedListener(listener: suspend () -> Unit)

    fun detachOnWalletWipedListener(listener: suspend () -> Unit)

    fun processDirectTransaction(tx: Transaction)

    @Throws(LeftoverBalanceException::class)
    fun checkSendingConditions(address: Address?, amount: Coin)

    fun observeMostRecentTransaction(): Flow<Transaction>
    fun observeTotalBalance(): Flow<Coin>
    fun lockOutput(outPoint: TransactionOutPoint): Boolean
}
