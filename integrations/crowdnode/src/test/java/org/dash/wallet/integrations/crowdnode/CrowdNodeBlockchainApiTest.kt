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

package org.dash.wallet.integrations.crowdnode

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import org.bitcoinj.core.Address
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.Utils
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.WalletTransaction
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeBlockchainApi
import org.junit.Test
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Reproduces the on-chain state of a linked online account whose API confirmation
 * (0.00054321) was paid from the same wallet that holds the account address.
 * All three transactions are real mainnet transactions.
 */
class CrowdNodeBlockchainApiTest {
    private val params = MainNetParams.get()

    // exchange withdrawal funding the primary address XoAJUhPEcu33i41QSrs24A5MGyqg7HSPQz
    private val fundingData = "01000000017037fa1cfde44df6e4139ac655084fa8c6f88a351d1b5efef73b25750191f2c1010000006a473044022030a0df5aa264cd5bf82f5d5457eeca7c3f1c7d20fe8ad219146dd894d690b41202205b684a949453b805c1024da89afae16dafa37c36011253a5e18fd3d2935cb48f0121035753529eebe5bcd9bdd913aff804040a2777c8204b2fd7884103ec01312ecae1ffffffff0289b12928000000001976a91488d2fba4cd0c68776e82050a0da8129dd48e336788ac6d1462f6010000001976a9147741896f6df349eb5bd8feea989379461456912b88ac00000000" // ktlint-disable max-line-length

    // API confirmation: 0.00054321 from XoAJ… (primary) to Xq5k… (account address),
    // change to XdbP… — an entirely-self transaction when all three are in one wallet
    private val confirmationData = "0100000001b9151ff4d28b6b1e2782217cea55f3bfb408e896b5f64b7a6034bdc97e745291000000006a47304402203439f0eb3fec9c20f812d9c6dcdac946e8d2e2626ba4f12ea41526bb8ac67de202200b3dc467df9f2e5a62d10764037127abc7f16879d2127cfc3d48c7964ac501e4012103aa924f52654efa97f50d173417dcdef0a805c45b25f37204ee44fd6bf2a3910effffffff0231d40000000000001976a9149de6b3dc2300e31de5211199a21092ce0945af5088ac75dc2828000000001976a9141fe06a217da8f8f330f02f011f5c8f0b5ab659e688ac00000000" // ktlint-disable max-line-length

    // the confirmation forwarded back to CrowdNode: 0.00054128 (54321 minus fee) to XjbaGWaGnvEtuQAUoBgDxJWe8ZNv45upG2
    private val forwardedData = "010000000175b532355a4e42561b85b7f302fa4077c8da68ba6071e6af3d3c809c458a85c1000000006a47304402201d9e1f0b8d24b340ce7e36c0f1c16c5cd983bc387a19573563b95d1143e079ee0220718db4bf3d062d34f678bbbef9a6cfbfc0ca882b5db59fe3c0440c6cc69b682f0121037bbeff2e3473f078480ba03daa8d826f4b5705c8be815dea3d65bd800b31f9d2ffffffff0170d30000000000001976a91461ba0f43e13c1cdf5bc81db6bc46fdaf162f038c88ac00000000" // ktlint-disable max-line-length

    private val primaryAddress = "XoAJUhPEcu33i41QSrs24A5MGyqg7HSPQz"
    private val accountAddress = "Xq5kHsEhFdprigZYkrZivxruxmNxiNLCrJ"
    private val changeAddress = "XdbPcoFAKKmKEcEhXnrSjDBWyAFucQQFX3"

    private fun createBag(vararg ownedAddresses: String): TransactionBag {
        val ownedHashes = ownedAddresses.map { Address.fromBase58(params, it).hash.toList() }

        return object : TransactionBag {
            override fun isPubKeyHashMine(pubKeyHash: ByteArray, scriptType: Script.ScriptType?) =
                ownedHashes.contains(pubKeyHash.toList())
            override fun isWatchedScript(script: Script) = false
            override fun isPubKeyMine(pubKey: ByteArray) = false
            override fun isPayToScriptHashMine(payToScriptHash: ByteArray) = false
            override fun isCoinJoinPubKeyHashMine(pubKeyHash: ByteArray, scriptType: Script.ScriptType?) = false
            override fun isCoinJoinPubKeyMine(pubKey: ByteArray) = false
            override fun isCoinJoinPayToScriptHashMine(payToScriptHash: ByteArray) = false
            override fun getTransactionPool(pool: WalletTransaction.Pool): Map<Sha256Hash, Transaction> = mapOf()
            override fun isFullyMixed(output: TransactionOutput) = false
            override fun isLockedOutput(outPoint: TransactionOutPoint) = false
            override fun lockOutput(outPoint: TransactionOutPoint) = false
        }
    }

    private fun createApi(bag: TransactionBag, walletTransactions: List<Transaction>): CrowdNodeBlockchainApi {
        val walletData = mock<WalletDataProvider> {
            on { networkParameters } doReturn params
            on { transactionBag } doReturn bag
            on { getTransactions(anyVararg()) } doAnswer { invocation ->
                val filters = invocation.arguments.flatMap { argument ->
                    when (argument) {
                        is TransactionFilter -> listOf(argument)
                        is Array<*> -> argument.filterIsInstance<TransactionFilter>()
                        else -> listOf()
                    }
                }
                walletTransactions.filter { tx -> filters.any { it.matches(tx) } }
            }
        }

        return CrowdNodeBlockchainApi(mock<SendPaymentService>(), walletData)
    }

    @Test
    fun getApiAddressConfirmationTx_selfPaidConfirmation_isFound() {
        // the primary address and the account address are both in this wallet,
        // so the confirmation is an entirely-self transaction with a negative net value
        val bag = createBag(primaryAddress, accountAddress, changeAddress)

        val fundingTx = Transaction(params, Utils.HEX.decode(fundingData))
        val confirmationTx = Transaction(params, Utils.HEX.decode(confirmationData))
        val forwardedTx = Transaction(params, Utils.HEX.decode(forwardedData))
        confirmationTx.inputs[0].connect(fundingTx.outputs[0])
        forwardedTx.inputs[0].connect(confirmationTx.outputs[0])

        val api = createApi(bag, listOf(fundingTx, confirmationTx, forwardedTx))
        val result = api.getApiAddressConfirmationTx()

        assertNotNull("self-paid API confirmation tx must be found", result)
        assertEquals(confirmationTx.txId, result!!.txId)
    }

    @Test
    fun getApiAddressConfirmationTx_externallyPaidConfirmation_isFound() {
        // only the account address is in this wallet; the confirmation
        // arrives as a regular incoming transaction with an unconnected input
        val bag = createBag(accountAddress)

        val confirmationTx = Transaction(params, Utils.HEX.decode(confirmationData))
        val forwardedTx = Transaction(params, Utils.HEX.decode(forwardedData))
        forwardedTx.inputs[0].connect(confirmationTx.outputs[0])

        val api = createApi(bag, listOf(confirmationTx, forwardedTx))
        val result = api.getApiAddressConfirmationTx()

        assertNotNull("externally paid API confirmation tx must be found", result)
        assertEquals(confirmationTx.txId, result!!.txId)
    }

    @Test
    fun getApiAddressConfirmationTx_confirmationNotForwarded_returnsNull() {
        val bag = createBag(primaryAddress, accountAddress, changeAddress)

        val fundingTx = Transaction(params, Utils.HEX.decode(fundingData))
        val confirmationTx = Transaction(params, Utils.HEX.decode(confirmationData))
        confirmationTx.inputs[0].connect(fundingTx.outputs[0])

        val api = createApi(bag, listOf(fundingTx, confirmationTx))

        assertNull("a confirmation without the forward to CrowdNode must not match", api.getApiAddressConfirmationTx())
    }
}
