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

package de.schildbach.wallet.util.transactions

import de.schildbach.wallet.transactions.TransactionWrapperHelper
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletTransaction
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import org.bitcoinj.core.Utils
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSetFactory
import org.junit.Before
import org.junit.Test

class TransactionWrapperHelperTest {
    private val networkParams = TestNet3Params.get()

    private val signUpData = "0100000001728d9b2f7080e4d404a0d5f1d6f1a1e5e8c08441934d65ceb70c8a0082f006da010000006a473044022054401d60d62e97d7f4ab5e1c826d520dde0282d8519806ff9ad42642dc12930002200e9d850b926191662d6faf3c9d01489d037710d3f43d1f01063dabfa31b4beec012102c9ec4d5cd8547b6811c0ecb9f18a6970443fa103ff30846ec369b5bf06a252a3ffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a9144a37287587b5c58c704ccdee322ab43521d3ecd288ac00000000"
    private val signUpRequestTx = Transaction(networkParams, Utils.HEX.decode(signUpData))
    private val acceptTermsResponseData = "02000000042607763cf6eceb2478060ead38fbb3151b7676b6a243e78b58c420a4ad99cb05010000006a47304402201f95f3a194bd51c521adcd46173d3d5c9bd2dd148004dd1da72e686fd6d946e4022020e34d85cd817aff0663b133915ca2eda5ecd5d5a93fba33f2e9644f1d1513a3012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffffe27ecbb210e98a5d2dba6e3bfa0732b8f6371155c3f8bd0420027d2eb3d24a7d010000006b483045022100c7d5c710ebdf8a2526389347823c3de83b3da498eeac5d1e9001e2e86f4cd0d002200e91ee98abc4f5fb5a78e8e80ed6fd17697a706e7118f87e545d8fdad65a845b012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff70a65da4b8d4438058c2e8f36811577cdb244d33c7973644386259135e3635a3010000006b483045022100d1c279574bdb0a4c72b6a11247f2945746b50f3a847c9c6925f0badfa8f5827a0220059884f1e9099fcfbb4966cced355e764ddf18bc60a3e03a3804c0c9b20618a4012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff4605e08cc9758029e89705c41872f063854684b5abf2020e56aca53f161b3fea000000006b483045022100f5afc8c1e722b25532b0a3561f0c37cf80bcd288a40fa0ced53d9a137f06dbc8022067c8ad28484b4a504f74cc7ad754ab4b87f0fbb46a4725e915b625eb000be8fd012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a914b889fb3449a36530c85d9689c4773c5cd1ba223388ac51844c8c060000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acb1f80a00"
    private val acceptTermsResponseTx = Transaction(networkParams, Utils.HEX.decode(acceptTermsResponseData))
    private val acceptTermsData = "010000000299378f2db43315876e11a9433c139ba8259181c20c321b64c35328a0867655d8000000006b483045022100cadd63226d6dbf0711d2abe6dab4d6ae8275dc1d273b3b9b8b6435459d81bce102206b2816298e66c78d5e3e8aa02721c06e12ce860ef1e356ee7faf19954204f8720121027c974ed291479646948719c1889aee27bc4e29af2a6c7236a92555d2b3348b97ffffffff8064383dc40802e7bc4dfb9962f930e663ee2bfbe120755f9c7f67230d8cdcf2010000006a47304402207c1c9716c01a72f61983692e5e24125048c23bf2366aec6ececaa848acdb3174022037b07e46e8e04f050c1898d6f343179ffd7bcbf30138b0d380e67ea85f1c1e030121027c974ed291479646948719c1889aee27bc4e29af2a6c7236a92555d2b3348b97ffffffff02204e0100000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac276b0a00000000001976a914c80f91cf7031ad1520661e2a6f9ff3b176bcf96588ac00000000"
    private val acceptTermsRequestTx = Transaction(networkParams, Utils.HEX.decode(acceptTermsData))
    private val welcomeData = "020000000263779831af3973f7f8f1c390c363c3eae19bcc60c0296852ecea832e16022769010000006a473044022042dcb3849c7018cc99879bcea881284c3a5848ae5caf4c7d1390a9cbde812e780220557da9f91b088c5a59db6ed82ea34e5f5a4f5d1bf10fa5ccff920c4a461ecb4a012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff90bd741046ab7e68d532ac0466920729f3070b1184a4703ac620601ff594d0ff000000006a47304402202b512d7a20279a1aed12dd05619a2951412ff3d0ded96327a43ddd02dba0c1ad0220337f62aafb3721575e2ccd3c6bac97591186e6cca92f54896e08c1cd529e7be6012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02244e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac5c1dc4680a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acfbdc0a00"
    private val welcomeResponseTx = Transaction(networkParams, Utils.HEX.decode(welcomeData))
    private val receivedData = "0200000001eb1543c0d57f5baa4f841b9017b7c9e056784b35c3771cbe3dbc7dc3d1f64f8e010000006b483045022100805c75979e175e7ed621ef7d22fe830a21137271e0f6bae52bb42a7386cf936102207770ccac9e5be357731fd01d205a7aec792413145ab966266a2e2f1df7182732012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02250fad02000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac10c9e24c000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acdbde0a00"
    private val receivedTx = Transaction(networkParams, Utils.HEX.decode(receivedData))
    private val topUpData = "0100000001fcb93a5a93588ece9b4b9b8ece83a7afa4e9d2ffd5da0b76ed30c8dff07498ba000000006a47304402203c0226cb59e0b512cea751cf0d44a52a1bb07c9628b26a58221d27665a48eccf0220107831d8e72ef41822bfaac8f5082a18e575f178fa581cc310c9591aefea8dd90121028b14bfe13b4e77af8d2b7e1da61b034bec0e36f9fd4ddea2c02537027ddec68dffffffff02bd850100000000001976a9148b6743bde3b5b5778220891e8572d2475c1c9e0d88aca0bb0d00000000001976a9144a37287587b5c58c704ccdee322ab43521d3ecd288ac00000000"
    private val topUpTx = Transaction(networkParams, Utils.HEX.decode(topUpData))

    @Before
    fun setup() {
        val topUpConnectedData = "0100000001e0ab1c7b601baebdccf03bc55787ad3957d8d13ba9a0a5e4d39c161302194aad000000006a473044022074266bfc5df38745604753ee0a48fa9ed7729c305b97a8816fa1c286da53bf0c02204ae1b458a3a956b8ad1a20c8c4f6c48dfe4ef05169afa77106a1816d07fb5c030121027c974ed291479646948719c1889aee27bc4e29af2a6c7236a92555d2b3348b97ffffffff0240420f00000000001976a91428fd6a3abc9633389c146b44f59243ac1ec3caac88ac629cd223000000001976a9140817e5a5adce5731e83f318fb725bd0e339effef88ac00000000"
        val topUpConnectedTx = Transaction(networkParams, Utils.HEX.decode(topUpConnectedData))
        topUpTx.inputs[0].connect(topUpConnectedTx.outputs[0])
        signUpRequestTx.inputs[0].connect(topUpTx.outputs[1])

        val acceptTermsResponseConnectedData = "0100000001fc44931460fcb2a3b366f4b967fb4bde573667c6bcee2eaae198e3c8ed1faff5000000006b483045022100832d93353b7651d8bcf38d9d450de4234e9dc3bd243199ab06fa775cc9096c9502200f7d574aaa4b52ac254aeaf372efa7833f245acefb4e9ae2b81a1faeffcd9016012103f5ca44dde27d2a4219ad6e66617ef2bfbeb11021e761e835021e781505650915ffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a914b889fb3449a36530c85d9689c4773c5cd1ba223388ac00000000"
        val acceptTermsResponseConnectedTx = Transaction(networkParams, Utils.HEX.decode(acceptTermsResponseConnectedData))
        acceptTermsResponseTx.inputs[3].connect(acceptTermsResponseConnectedTx.outputs[0])

        var acceptTermsConnected = "020000000580a3d49f19aa295f88f3cac50a7b8b03aa9827613194b343d1b4619154765037010000006a47304402205eff3b09ae40a57d647f390da127dfbc5d60def439b654818f5b3b7d7b6bc75902201af302957b373fa1478d9c8d4990ed682e25c8a65ce908dc098f7fd93731e5cb012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff7bb77f9e2029037811ae12e113139c1acfa94b43e889f49f51ac3ca092878e7a010000006a473044022034105004d1cd96f4e464d1735ef5c97f931a838d53f59e29a59f0c1b5fdae22c02204a157f4305dad527274c44f76dc560352e36bde13d9948d81e4606eaa3f18b9e012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff3b9ae62e792bdd6f79dce865951bff5e8f881c43f9bd405481f7e9fd4f7ad897010000006a4730440220623abe3320ce5d504c7128f05b0d8a6551726806cea5e4a29cdc41bf6310be1b0220396659b3636f4be5179324bcb8288218a35850b2004efa3a00074f2348441244012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffffd90fc886050e5676fb588af6982461bbab46f28032a613b8af4a47b80affb4ee010000006a473044022020da20cac78787d18a5eb89449b9386d5d66300a509a5e56904dbbf010ee3883022021aed84acbd7f1e572a85e9964709fa33332f36924ceb6d2b1547b8df0369c3e012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff8064383dc40802e7bc4dfb9962f930e663ee2bfbe120755f9c7f67230d8cdcf2000000006a47304402202db504962921331f6dfbe5b8ad9253ac692a9a104cd8dbf2fea8609cc268d1f402207c77072b00ada37e9ef1ca3e0bd9fe3ac962ff21e0c5475ffe2045b8d8c50c87012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a914c80f91cf7031ad1520661e2a6f9ff3b176bcf96588ac5c757038180000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9f470b00"
        var acceptTermsConnectedTx = Transaction(networkParams, Utils.HEX.decode(acceptTermsConnected))
        acceptTermsRequestTx.inputs[0].connect(acceptTermsConnectedTx.outputs[0])
        acceptTermsConnected = "0100000001f592b906b367e3bca0cfe7da733747baad88f17092cbbd03cb19bdeaddbe8d03010000006b4830450221008851ab86e3690334a4b17b842ccf5279af5a5048182faa2cd2cc1ea27bf4507202206576fe169513ffd05513244a23a97f44cb9359181877393d0dff05714818e7ab0121027c974ed291479646948719c1889aee27bc4e29af2a6c7236a92555d2b3348b97ffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a914c80f91cf7031ad1520661e2a6f9ff3b176bcf96588ac00000000"
        acceptTermsConnectedTx = Transaction(networkParams, Utils.HEX.decode(acceptTermsConnected))
        acceptTermsRequestTx.inputs[1].connect(acceptTermsConnectedTx.outputs[0])

        val welcomeConnected = "0200000002f7f6beb8d49ec4639394a663cd3ae08d9382ecfbb38e9cb85deaf835b74ad1be000000006a47304402202b467d0ae5f40633500096b01dbc5952efca40d50143739dc92f2b9fc8cf479c02206d3a04f11538ce4ff664b168abad0b02d135b3ec571b390616ac76f888e0ecaf012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffffd979b8815c9a17e956011b7b9767dcb1237501832392a1f5d2ed2e0d785754c2010000006a473044022079f2e9dd53d838978fe82a6034894c062381ac32bce190e0d862efff7e4a5ef002200eee7a5bd39f084cd8dc73d50d1fc1388e998750db3fadbd74ff2537bef0cd8e012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888acf91ec3680a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acfadc0a00"
        val welcomeConnectedTx = Transaction(networkParams, Utils.HEX.decode(welcomeConnected))
        welcomeResponseTx.inputs[0].connect(welcomeConnectedTx.outputs[0])

        val receivedConnected = "020000000110ae7a5bff9016348ea2babb8b614ccf498cc89b2dd00273b009874d33b5735a010000006b483045022100a8a27fd8a0a9579fccabb171604947a75223d27eb5ae28cc348ae9c682d24b2002205d27b13f124816789e940df33c78bf3dc3ef35ee1928cebb55ec32ed095f3e8f012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02304e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac2cd98f4f000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acdbde0a00"
        val receivedConnectedTx = Transaction(networkParams, Utils.HEX.decode(receivedConnected))
        receivedTx.inputs[0].connect(receivedConnectedTx.outputs[0])
    }

    @Test
    fun wrapTransactions_fullCrowdNodeSignUpTxSet_correctWrapping() {
        val wallet = mockk<Wallet>()
        every { wallet.context } returns Context(networkParams)

        val someTxData = "01000000015278aa3819d5663c38e83d98831131cbf4613028710e0807f1f3cc2c267a1d1c010000006a47304402201b32c065edb60618307f14e5f065b5ca12a9f77bfabc31bd60304730217b2cb10220135b503243dfb9a5a3e40b6b7049d6a94fcf6fb596ca8b1cd08e0389d38907920121034fc8962de581ea8bb89fd364f9560e5e3646744a7ce4ec45feade07135096997ffffffff0231d40000000000001976a9146788908cc67f13b9636fa1c50a18634dc7faf70288ac58e9da14000000001976a91497dcc58e0f473cca0318eaaa4d305c8b5d06f0b688ac00000000"
        val someTx = Transaction(networkParams, Utils.HEX.decode(someTxData))
        val connectedTxData = "01000000012fbfc99b81cae50e408e65978450c4176dac1abd79a750dcf7f197ae64953c8a010000006b483045022100f8b72d1516843ae7e2ce8667c53811b2c9d6eeeec74f30817ec61a08380f7e5802203b08f11294136baae1208baa704a03251d9965817beac69d5406f14c3e5624aa012102ff263761f3c8bb1a71a71faa5e9d930a28ff379f3f0b3c33be0bd41523d4b457ffffffff0231d40000000000001976a9140b627cdc0c1dc9f0a87c9491a18471e88dbfef2588ac6cbedb14000000001976a91485d306c762871bfb5572908b23dc9294ad14c44a88ac00000000"
        val connectedTx = Transaction(networkParams, Utils.HEX.decode(connectedTxData))
        someTx.inputs[0].connect(connectedTx.outputs[0])

        val allTransactions = setOf(
            someTx,
            signUpRequestTx,
            acceptTermsResponseTx,
            acceptTermsRequestTx,
            welcomeResponseTx,
            topUpTx,
            receivedTx
        )

        val hash1 = Utils.HEX.decode("28fd6a3abc9633389c146b44f59243ac1ec3caac")
        val hash2 = Utils.HEX.decode("8b6743bde3b5b5778220891e8572d2475c1c9e0d")
        val hash3 = Utils.HEX.decode("4a37287587b5c58c704ccdee322ab43521d3ecd2")

        val bagMock = mockk<TransactionBag>()
        every { bagMock.isPubKeyHashMine(any(), any()) } returns false
        every { bagMock.isPubKeyHashMine(eq(hash1), any()) } returns true
        every { bagMock.isPubKeyHashMine(eq(hash2), any()) } returns true
        every { bagMock.isPubKeyHashMine(eq(hash3), any()) } returns true
        every { bagMock.isWatchedScript(any())} returns true
        every { bagMock.getTransactionPool(WalletTransaction.Pool.UNSPENT)} returns mapOf()
        every { bagMock.getTransactionPool(WalletTransaction.Pool.PENDING)} returns mapOf()
        every { bagMock.getTransactionPool(WalletTransaction.Pool.SPENT)} returns allTransactions.associateBy({it.txId}, {it})

        val crowdNodeWrapperFactory = FullCrowdNodeSignUpTxSetFactory(networkParams, bagMock)
        val crowdNodeWrapper = crowdNodeWrapperFactory.wrappers.first()!!
        val wrappedTransactions = TransactionWrapperHelper.wrapTransactions(
            allTransactions,
            crowdNodeWrapperFactory
        )

        assertEquals("Must have CrowdNode wrapper and 2 anon wrappers:", 3, wrappedTransactions.size)
        assertEquals("CrowdNode wrapper must have 5 transactions", 5, crowdNodeWrapper.transactions.size)
    }
}