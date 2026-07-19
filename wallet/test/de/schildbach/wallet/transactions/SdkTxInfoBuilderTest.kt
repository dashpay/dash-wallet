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

package de.schildbach.wallet.transactions

import de.schildbach.wallet.service.platform.sdk.SdkSeamTxSnapshot
import de.schildbach.wallet.service.platform.sdk.l1TxUiRecord
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.Utils
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.CoinsFromAddressTxFilter
import org.dash.wallet.common.transactions.filters.CoinsToAddressTxFilter
import org.junit.Test

/**
 * Filter-semantics parity for the POST-CUTOVER seam: the same raw transactions
 * [NeutralTxFilterParityTest] pushes through the dashj-fed [TxInfoConverter] are
 * built here from an SDK-store-shaped [SdkSeamTxSnapshot] via [SdkTxInfoBuilder],
 * and the neutral filters must reach identical verdicts. Also pins the SDK-truth
 * field mappings (lock state from the context code, ownership from the TXO set,
 * spentBy from the TXO spender column) and the fallback/missing-data behavior.
 */
class SdkTxInfoBuilderTest {
    private val params = TestNet3Params.get()

    init {
        // The dashj-fed TxInfo conversion (Transaction.getConfidence) needs a
        // propagated dashj Context on the test thread.
        org.bitcoinj.core.Context.propagate(org.bitcoinj.core.Context.getOrCreate(params))
    }
    private val now = 1_753_000_000L

    // The CoinsToAddress transactions from NeutralTxFilterParityTest.
    private val toAddressTxData = "01000000033f90cbc2d751c77358b3ff37efd72936b389a17b9ec72bdec4678394814cfe2d000000006a473044022050d2f3b6f097f1973b29bb5a0e98f307f6fc338bb8d29e4a7eb257eebd147ccd022055f88aa06cf90aec97991db9c351fd622fa60fe2cb6bbe6df2ecfef03ca047fa012102d336120a91d7d3497056715f6078e36c56e84c41038cf630260ef3245f6ba39effffffff94cae0fa480e004218a66ea7eae8c0a1a39dbd8ebba966004ddfdcac1e11f089000000006b483045022100ed1fbe54b90c8d69e616b79ba5e03e192bdee6b26f66d40d9da14ae7c7e64a9c022062c54fb1635937a38f3b43b504777c9faf357734cad6f53130870f7e980a3be60121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff3e2611f35c7a2fefadce6b115ce8e14b31b627667af9c04909c0ddcceb8294a3000000006a473044022036bed2e8600ed1a715618ca398553254c14fcea824b77ed784cee5f5b23b84df022041c4821e6e639169ddc891e4d6b4e146e5f4684e5687daf5fcce2fd1f73392230121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff0260182300000000001976a9140205411ec940f9139ea72e3a999d21fceff671e688ac4dc27200000000001976a91425b2b9126bf32e6115a813d019e72b7b9106211b88ac00000000" // ktlint-disable max-line-length

    // The CoinsFromAddress spender + its connected (spent-output) transaction
    // from NeutralTxFilterParityTest: input[3] spends connectedTx.outputs[0].
    private val fromAddressTxData = "02000000042607763cf6eceb2478060ead38fbb3151b7676b6a243e78b58c420a4ad99cb05010000006a47304402201f95f3a194bd51c521adcd46173d3d5c9bd2dd148004dd1da72e686fd6d946e4022020e34d85cd817aff0663b133915ca2eda5ecd5d5a93fba33f2e9644f1d1513a3012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffffe27ecbb210e98a5d2dba6e3bfa0732b8f6371155c3f8bd0420027d2eb3d24a7d010000006b483045022100c7d5c710ebdf8a2526389347823c3de83b3da498eeac5d1e9001e2e86f4cd0d002200e91ee98abc4f5fb5a78e8e80ed6fd17697a706e7118f87e545d8fdad65a845b012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff70a65da4b8d4438058c2e8f36811577cdb244d33c7973644386259135e3635a3010000006b483045022100d1c279574bdb0a4c72b6a11247f2945746b50f3a847c9c6925f0badfa8f5827a0220059884f1e9099fcfbb4966cced355e764ddf18bc60a3e03a3804c0c9b20618a4012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff4605e08cc9758029e89705c41872f063854684b5abf2020e56aca53f161b3fea000000006b483045022100f5afc8c1e722b25532b0a3561f0c37cf80bcd288a40fa0ced53d9a137f06dbc8022067c8ad28484b4a504f74cc7ad754ab4b87f0fbb46a4725e915b625eb000be8fd012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a914b889fb3449a36530c85d9689c4773c5cd1ba223388ac51844c8c060000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acb1f80a00" // ktlint-disable max-line-length
    private val fromAddressConnectedTxData = "0100000001fc44931460fcb2a3b366f4b967fb4bde573667c6bcee2eaae198e3c8ed1faff5000000006b483045022100832d93353b7651d8bcf38d9d450de4234e9dc3bd243199ab06fa775cc9096c9502200f7d574aaa4b52ac254aeaf372efa7833f245acefb4e9ae2b81a1faeffcd9016012103f5ca44dde27d2a4219ad6e66617ef2bfbeb11021e761e835021e781505650915ffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a914b889fb3449a36530c85d9689c4773c5cd1ba223388ac00000000" // ktlint-disable max-line-length

    private fun tx(hex: String) = Transaction(params, Utils.HEX.decode(hex))

    private fun recordFor(
        tx: Transaction,
        net: Long = 0L,
        fee: Long? = null,
        context: Int = 1,
        direction: Int = 0,
        firstSeenSec: Long = now
    ) = l1TxUiRecord(tx.txId.reversedBytes, net, fee, context, direction, firstSeenSec, 0)

    private fun snapshotOf(
        walletTxs: List<Pair<Transaction, de.schildbach.wallet.service.platform.sdk.L1TxUiRecord>>,
        extraPayloads: List<Transaction> = emptyList(),
        mineOutpoints: Set<String> = emptySet(),
        spenderByOutpoint: Map<String, String> = emptyMap()
    ): SdkSeamTxSnapshot {
        val payloads = HashMap<String, ByteArray>()
        walletTxs.forEach { (transaction, _) -> payloads[transaction.txId.toString()] = transaction.bitcoinSerialize() }
        extraPayloads.forEach { payloads[it.txId.toString()] = it.bitcoinSerialize() }
        return SdkSeamTxSnapshot(
            walletRecords = walletTxs.map { it.second },
            payloadByTxid = payloads,
            mineOutpoints = mineOutpoints,
            spenderByOutpoint = spenderByOutpoint
        )
    }

    private fun build(snapshot: SdkSeamTxSnapshot, fallback: (org.bitcoinj.core.Sha256Hash) -> Transaction? = { null }) =
        SdkTxInfoBuilder.buildTxInfos(snapshot, params, fallback)

    // ── Filter parity: CoinsToAddressTxFilter ─────────────────────────

    @Test
    fun coinsToAddress_matchesLikeDashjPath() {
        val transaction = tx(toAddressTxData)
        assertEquals("ceb0e5920ade494bb4f08f62f9c059c57a60841a9ef8b968e7dde247eb10f9e2", transaction.txId.toString())

        val infos = build(snapshotOf(listOf(transaction to recordFor(transaction, net = 2_300_000))))
        val txInfo = infos.getValue(transaction.txId.toString())

        val filter = CoinsToAddressTxFilter("yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi", Dash(Coin.parseCoin("0.023").value))
        assertTrue("SDK-fed TxInfo must match like the dashj-fed one", filter.matches(txInfo))

        val wrongAmount = CoinsToAddressTxFilter("yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi", Dash.valueOf(1))
        assertFalse(wrongAmount.matches(txInfo))
    }

    // ── Filter parity: CoinsFromAddressTxFilter (the CrowdNode response shape) ──

    @Test
    fun coinsFromAddress_resolvesConnectedInputThroughSdkPayloads() {
        val transaction = tx(fromAddressTxData)
        val connectedTx = tx(fromAddressConnectedTxData)
        // The raw pair genuinely references each other (no manual connect()).
        assertEquals(connectedTx.txId, transaction.inputs[3].outpoint.hash)

        val infos = build(
            snapshotOf(
                walletTxs = listOf(transaction to recordFor(transaction, net = 20_002)),
                extraPayloads = listOf(connectedTx)
            )
        )
        val txInfo = infos.getValue(transaction.txId.toString())

        val filter = CoinsFromAddressTxFilter("yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy", Dash.valueOf(20002))
        assertTrue("SDK-fed TxInfo must match like the dashj-fed one", filter.matches(txInfo))
        assertEquals("Wrong TO address", "yd9CUc7wvATUS3GfdmcAhRZhG7719jhNf9", filter.toAddress)
    }

    @Test
    fun coinsFromAddress_dashjHeldWalletFallbackResolvesUnknownSource() {
        val transaction = tx(fromAddressTxData)
        val connectedTx = tx(fromAddressConnectedTxData)

        // The SDK store does not know the source tx; the held dashj wallet does.
        val infos = build(
            snapshotOf(listOf(transaction to recordFor(transaction, net = 20_002)))
        ) { hash -> if (hash == connectedTx.txId) connectedTx else null }
        val txInfo = infos.getValue(transaction.txId.toString())

        assertTrue(CoinsFromAddressTxFilter("yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy", Dash.valueOf(20002)).matches(txInfo))
    }

    @Test
    fun coinsFromAddress_unresolvableInputStaysUnconnected() {
        val transaction = tx(fromAddressTxData)

        // Neither the SDK store nor the held wallet knows the source txs —
        // exactly like a dashj input that was never connected: null
        // connectedAddress, no match, no fabricated data.
        val infos = build(snapshotOf(listOf(transaction to recordFor(transaction, net = 20_002))))
        val txInfo = infos.getValue(transaction.txId.toString())

        assertTrue(txInfo.inputs.all { it.connectedAddress == null && it.connectedIsMine == null })
        assertFalse(CoinsFromAddressTxFilter("yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy", Dash.valueOf(20002)).matches(txInfo))
    }

    @Test
    fun parity_dashjFedAndSdkFedVerdictsAgree() {
        val transaction = tx(fromAddressTxData)
        val connectedTx = tx(fromAddressConnectedTxData)

        // dashj-fed path (as in NeutralTxFilterParityTest).
        transaction.inputs[3].connect(connectedTx.outputs[0])
        val dashjFed = transaction.toTxInfo(emptyBag(), params)

        // SDK-fed path over the same raw bytes.
        val sdkFed = build(
            snapshotOf(
                walletTxs = listOf(tx(fromAddressTxData) to recordFor(transaction, net = 20_002)),
                extraPayloads = listOf(connectedTx)
            )
        ).getValue(transaction.txId.toString())

        for (filter in listOf(
            CoinsFromAddressTxFilter("yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy", Dash.valueOf(20002)),
            CoinsFromAddressTxFilter("yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy", Dash.valueOf(11111)),
            CoinsFromAddressTxFilter("yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi", Dash.valueOf(20002))
        )) {
            assertEquals(filter.matches(dashjFed), filter.matches(sdkFed))
        }
        for (filter in listOf(
            CoinsToAddressTxFilter("yTay8b7iRUgnA6vYK5qtVdF7aCCzcGGDMs", Dash.valueOf(20002)),
            CoinsToAddressTxFilter("yTay8b7iRUgnA6vYK5qtVdF7aCCzcGGDMs", Dash.valueOf(1))
        )) {
            assertEquals(filter.matches(transaction.toTxInfo(emptyBag(), params)), filter.matches(sdkFed))
        }
    }

    // ── SDK-truth field mappings ──────────────────────────────────────

    @Test
    fun lockStateComesFromTheContextCode() {
        val transaction = tx(toAddressTxData)
        fun info(context: Int): TxInfo =
            build(snapshotOf(listOf(transaction to recordFor(transaction, context = context))))
                .getValue(transaction.txId.toString())

        assertFalse(info(0).isLocked)
        assertTrue(info(0).isPending)
        for (context in 1..3) {
            assertTrue("context $context must count as locked", info(context).isLocked)
            assertFalse(info(context).isPending)
        }
    }

    @Test
    fun amountsComeFromTheSdkRowNotTheParse() {
        val transaction = tx(toAddressTxData)
        val info = build(
            snapshotOf(listOf(transaction to recordFor(transaction, net = -1_000_146, fee = 146)))
        ).getValue(transaction.txId.toString())

        assertEquals(-1_000_146L, info.netValueDuffs)
        assertEquals(146L, info.feeDuffs)
        assertEquals(now * 1000, info.updateTimeMillis)
    }

    @Test
    fun outputOwnershipComesFromTheTxoSet() {
        val transaction = tx(toAddressTxData)
        val txid = transaction.txId.toString()
        val info = build(
            snapshotOf(
                listOf(transaction to recordFor(transaction)),
                mineOutpoints = setOf("$txid:1")
            )
        ).getValue(txid)

        assertFalse(info.outputs[0].isMine)
        assertTrue(info.outputs[1].isMine)
        assertEquals(2, info.outputs.size)
        assertEquals(0, info.outputs[0].index)
        assertEquals(Coin.parseCoin("0.023").value, info.outputs[0].valueDuffs)
    }

    @Test
    fun spentByResolvesOneLevelDeepThroughTheTxoSpenderColumn() {
        val spender = tx(fromAddressTxData)
        val spent = tx(fromAddressConnectedTxData)
        val spentTxid = spent.txId.toString()

        val infos = build(
            snapshotOf(
                walletTxs = listOf(
                    spent to recordFor(spent, net = 152_000),
                    spender to recordFor(spender, net = -152_000, direction = 1)
                ),
                spenderByOutpoint = mapOf("$spentTxid:0" to spender.txId.toString())
            )
        )

        val spentInfo = infos.getValue(spentTxid)
        assertEquals(spender.txId.toString(), spentInfo.outputs[0].spentBy?.txId)
        assertNull(spentInfo.outputs[1].spentBy)
        // Depth is 1, mirroring TxInfoConverter: the spender's own outputs
        // don't chain further.
        assertTrue(spentInfo.outputs[0].spentBy!!.outputs.all { it.spentBy == null })
    }

    @Test
    fun entirelySelfComesFromTheSdkDirection() {
        val transaction = tx(toAddressTxData)
        fun info(direction: Int): TxInfo =
            build(snapshotOf(listOf(transaction to recordFor(transaction, direction = direction))))
                .getValue(transaction.txId.toString())

        assertFalse(info(0).isEntirelySelf)
        assertFalse(info(1).isEntirelySelf)
        assertTrue(info(2).isEntirelySelf)
        assertTrue(info(3).isEntirelySelf)
    }

    @Test
    fun rawHandleIsAbsentAndRawHexPresent() {
        val transaction = tx(toAddressTxData)
        val info = build(snapshotOf(listOf(transaction to recordFor(transaction))))
            .getValue(transaction.txId.toString())

        // Honest gap: no live dashj wallet transaction exists behind an
        // SDK-fed snapshot (see the builder's docs).
        assertNull(info.raw)
        assertEquals(toAddressTxData, info.rawHex)
    }

    private fun emptyBag() = object : org.bitcoinj.core.TransactionBag {
        override fun isPubKeyHashMine(pubKeyHash: ByteArray, scriptType: org.bitcoinj.script.Script.ScriptType?) = false
        override fun isWatchedScript(script: org.bitcoinj.script.Script) = false
        override fun isPubKeyMine(pubKey: ByteArray) = false
        override fun isPayToScriptHashMine(payToScriptHash: ByteArray) = false
        override fun isCoinJoinPubKeyHashMine(pubKeyHash: ByteArray, scriptType: org.bitcoinj.script.Script.ScriptType?) = false
        override fun isCoinJoinPubKeyMine(pubKey: ByteArray) = false
        override fun isCoinJoinPayToScriptHashMine(payToScriptHash: ByteArray) = false
        override fun getTransactionPool(pool: org.bitcoinj.wallet.WalletTransaction.Pool): Map<org.bitcoinj.core.Sha256Hash, Transaction> = mapOf()
        override fun isFullyMixed(output: org.bitcoinj.core.TransactionOutput) = false
        override fun isLockedOutput(outPoint: org.bitcoinj.core.TransactionOutPoint) = false
        override fun lockOutput(outPoint: org.bitcoinj.core.TransactionOutPoint) = false
    }
}
