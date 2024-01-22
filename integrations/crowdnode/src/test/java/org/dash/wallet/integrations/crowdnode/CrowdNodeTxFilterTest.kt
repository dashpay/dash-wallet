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

package org.dash.wallet.integrations.crowdnode

import junit.framework.TestCase.*
import org.bitcoinj.core.*
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeAPIConfirmationForwarded
import org.dash.wallet.integrations.crowdnode.transactions.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub

class CrowdNodeTxFilterTest {
    private val networkParams = TestNet3Params.get()

    private val signUpData = "0100000001f15743be9d858f7e6213bca8262bda38b9b59d44747051899533764cd3ca6606000000006a4730440220412fb2a56090bc271d25fd66a095749bc8c9d4b8200716ed452e91870dddc43702206c3b42a0f3ef11da3a563960c9ac208b6d4da02bafecb06eb7a28d29f8b7cb0f012103691a23ea571114b17de25c310e2b5f978551e1547d1fa465fdb6bb72d17ba3adffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac00000000" // ktlint-disable max-line-length
    private val signUpRequestTx = Transaction(networkParams, Utils.HEX.decode(signUpData))
    private val acceptTermsData = "010000000299378f2db43315876e11a9433c139ba8259181c20c321b64c35328a0867655d8000000006b483045022100cadd63226d6dbf0711d2abe6dab4d6ae8275dc1d273b3b9b8b6435459d81bce102206b2816298e66c78d5e3e8aa02721c06e12ce860ef1e356ee7faf19954204f8720121027c974ed291479646948719c1889aee27bc4e29af2a6c7236a92555d2b3348b97ffffffff8064383dc40802e7bc4dfb9962f930e663ee2bfbe120755f9c7f67230d8cdcf2010000006a47304402207c1c9716c01a72f61983692e5e24125048c23bf2366aec6ececaa848acdb3174022037b07e46e8e04f050c1898d6f343179ffd7bcbf30138b0d380e67ea85f1c1e030121027c974ed291479646948719c1889aee27bc4e29af2a6c7236a92555d2b3348b97ffffffff02204e0100000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac276b0a00000000001976a914c80f91cf7031ad1520661e2a6f9ff3b176bcf96588ac00000000" // ktlint-disable max-line-length
    private val acceptTermsRequestTx = Transaction(networkParams, Utils.HEX.decode(acceptTermsData))
    private val welcomeData = "020000000263779831af3973f7f8f1c390c363c3eae19bcc60c0296852ecea832e16022769010000006a473044022042dcb3849c7018cc99879bcea881284c3a5848ae5caf4c7d1390a9cbde812e780220557da9f91b088c5a59db6ed82ea34e5f5a4f5d1bf10fa5ccff920c4a461ecb4a012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff90bd741046ab7e68d532ac0466920729f3070b1184a4703ac620601ff594d0ff000000006a47304402202b512d7a20279a1aed12dd05619a2951412ff3d0ded96327a43ddd02dba0c1ad0220337f62aafb3721575e2ccd3c6bac97591186e6cca92f54896e08c1cd529e7be6012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02244e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac5c1dc4680a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acfbdc0a00" // ktlint-disable max-line-length
    private val welcomeResponseTx = Transaction(networkParams, Utils.HEX.decode(welcomeData))
    private val receivedData = "0200000001eb1543c0d57f5baa4f841b9017b7c9e056784b35c3771cbe3dbc7dc3d1f64f8e010000006b483045022100805c75979e175e7ed621ef7d22fe830a21137271e0f6bae52bb42a7386cf936102207770ccac9e5be357731fd01d205a7aec792413145ab966266a2e2f1df7182732012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02250fad02000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac10c9e24c000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acdbde0a00" // ktlint-disable max-line-length
    private val receivedTx = Transaction(networkParams, Utils.HEX.decode(receivedData))

    @Before
    fun setup() {
        val signupConnected = "0100000001a14301088210333bd5d0959624b153d6d0dcfd5a67813ec11df4879808b1b6ac000000006a473044022000c5bb339303d916de5765446a93a1513d151407c598f0f7aba79ea62795892b022012f97f16d00876b08d8e55a8c99ad4b009615789c6e4322cdb760f4a649d3ed5012103626557ad8e11b2bf7004683e2bd634579329b00902e67177c768bfa76fc3796bffffffff02a0bb0d00000000001976a914f57766c540e7e165092e739e115383bd04d2c21888acfdd98a00000000001976a914bd065b89a786f96a4529a4dacc0bb4a14268147088ac00000000" // ktlint-disable max-line-length
        val signupConnectedTx = Transaction(networkParams, Utils.HEX.decode(signupConnected))
        signUpRequestTx.inputs[0].connect(signupConnectedTx.outputs[0])

        var acceptTermsConnected = "020000000580a3d49f19aa295f88f3cac50a7b8b03aa9827613194b343d1b4619154765037010000006a47304402205eff3b09ae40a57d647f390da127dfbc5d60def439b654818f5b3b7d7b6bc75902201af302957b373fa1478d9c8d4990ed682e25c8a65ce908dc098f7fd93731e5cb012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff7bb77f9e2029037811ae12e113139c1acfa94b43e889f49f51ac3ca092878e7a010000006a473044022034105004d1cd96f4e464d1735ef5c97f931a838d53f59e29a59f0c1b5fdae22c02204a157f4305dad527274c44f76dc560352e36bde13d9948d81e4606eaa3f18b9e012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff3b9ae62e792bdd6f79dce865951bff5e8f881c43f9bd405481f7e9fd4f7ad897010000006a4730440220623abe3320ce5d504c7128f05b0d8a6551726806cea5e4a29cdc41bf6310be1b0220396659b3636f4be5179324bcb8288218a35850b2004efa3a00074f2348441244012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffffd90fc886050e5676fb588af6982461bbab46f28032a613b8af4a47b80affb4ee010000006a473044022020da20cac78787d18a5eb89449b9386d5d66300a509a5e56904dbbf010ee3883022021aed84acbd7f1e572a85e9964709fa33332f36924ceb6d2b1547b8df0369c3e012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff8064383dc40802e7bc4dfb9962f930e663ee2bfbe120755f9c7f67230d8cdcf2000000006a47304402202db504962921331f6dfbe5b8ad9253ac692a9a104cd8dbf2fea8609cc268d1f402207c77072b00ada37e9ef1ca3e0bd9fe3ac962ff21e0c5475ffe2045b8d8c50c87012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a914c80f91cf7031ad1520661e2a6f9ff3b176bcf96588ac5c757038180000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9f470b00" // ktlint-disable max-line-length
        var acceptTermsConnectedTx = Transaction(networkParams, Utils.HEX.decode(acceptTermsConnected))
        acceptTermsRequestTx.inputs[0].connect(acceptTermsConnectedTx.outputs[0])
        acceptTermsConnected = "0100000001f592b906b367e3bca0cfe7da733747baad88f17092cbbd03cb19bdeaddbe8d03010000006b4830450221008851ab86e3690334a4b17b842ccf5279af5a5048182faa2cd2cc1ea27bf4507202206576fe169513ffd05513244a23a97f44cb9359181877393d0dff05714818e7ab0121027c974ed291479646948719c1889aee27bc4e29af2a6c7236a92555d2b3348b97ffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a914c80f91cf7031ad1520661e2a6f9ff3b176bcf96588ac00000000" // ktlint-disable max-line-length
        acceptTermsConnectedTx = Transaction(networkParams, Utils.HEX.decode(acceptTermsConnected))
        acceptTermsRequestTx.inputs[1].connect(acceptTermsConnectedTx.outputs[0])

        val welcomeConnected = "0200000002f7f6beb8d49ec4639394a663cd3ae08d9382ecfbb38e9cb85deaf835b74ad1be000000006a47304402202b467d0ae5f40633500096b01dbc5952efca40d50143739dc92f2b9fc8cf479c02206d3a04f11538ce4ff664b168abad0b02d135b3ec571b390616ac76f888e0ecaf012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffffd979b8815c9a17e956011b7b9767dcb1237501832392a1f5d2ed2e0d785754c2010000006a473044022079f2e9dd53d838978fe82a6034894c062381ac32bce190e0d862efff7e4a5ef002200eee7a5bd39f084cd8dc73d50d1fc1388e998750db3fadbd74ff2537bef0cd8e012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888acf91ec3680a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acfadc0a00" // ktlint-disable max-line-length
        val welcomeConnectedTx = Transaction(networkParams, Utils.HEX.decode(welcomeConnected))
        welcomeResponseTx.inputs[0].connect(welcomeConnectedTx.outputs[0])

        val receivedConnected = "020000000110ae7a5bff9016348ea2babb8b614ccf498cc89b2dd00273b009874d33b5735a010000006b483045022100a8a27fd8a0a9579fccabb171604947a75223d27eb5ae28cc348ae9c682d24b2002205d27b13f124816789e940df33c78bf3dc3ef35ee1928cebb55ec32ed095f3e8f012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02304e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac2cd98f4f000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acdbde0a00" // ktlint-disable max-line-length
        val receivedConnectedTx = Transaction(networkParams, Utils.HEX.decode(receivedConnected))
        receivedTx.inputs[0].connect(receivedConnectedTx.outputs[0])
    }

    @Test
    fun signUpTxFilter_correctMatch() {
        val filter = CrowdNodeSignUpTx(networkParams)

        assertTrue("SignUp tx doesn't match", filter.matches(signUpRequestTx))
        assertEquals(1, filter.fromAddresses.size)
        assertEquals("yihMSMoesHX1JhbntTiV5Nptf5NLrmFMCu", filter.fromAddresses.first().toBase58())

        var notSignUpData = "01000000033f90cbc2d751c77358b3ff37efd72936b389a17b9ec72bdec4678394814cfe2d000000006a473044022050d2f3b6f097f1973b29bb5a0e98f307f6fc338bb8d29e4a7eb257eebd147ccd022055f88aa06cf90aec97991db9c351fd622fa60fe2cb6bbe6df2ecfef03ca047fa012102d336120a91d7d3497056715f6078e36c56e84c41038cf630260ef3245f6ba39effffffff94cae0fa480e004218a66ea7eae8c0a1a39dbd8ebba966004ddfdcac1e11f089000000006b483045022100ed1fbe54b90c8d69e616b79ba5e03e192bdee6b26f66d40d9da14ae7c7e64a9c022062c54fb1635937a38f3b43b504777c9faf357734cad6f53130870f7e980a3be60121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff3e2611f35c7a2fefadce6b115ce8e14b31b627667af9c04909c0ddcceb8294a3000000006a473044022036bed2e8600ed1a715618ca398553254c14fcea824b77ed784cee5f5b23b84df022041c4821e6e639169ddc891e4d6b4e146e5f4684e5687daf5fcce2fd1f73392230121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff0260182300000000001976a9140205411ec940f9139ea72e3a999d21fceff671e688ac4dc27200000000001976a91425b2b9126bf32e6115a813d019e72b7b9106211b88ac00000000" // ktlint-disable max-line-length
        var notSignUpTx = Transaction(networkParams, Utils.HEX.decode(notSignUpData))
        assertEquals(
            notSignUpTx.txId.toString(),
            "ceb0e5920ade494bb4f08f62f9c059c57a60841a9ef8b968e7dde247eb10f9e2"
        )
        assertFalse("Tx matches but should not", filter.matches(notSignUpTx))

        notSignUpData = "02000000024b86656e0590d048c666970225930d5806f746646eea0982be81fb354114e60d010000006a4730440220318c122e24d780b6123f001eb7fb006eda71a17067f25c96f067261da2fab4290220351d6a75c278d780550f0a5494082c749ed7737b4905c4383a206154ab4b7f94012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff476e14bb4fa20abc1fd23ef0ad17c2b65a6cf8959f51cfc412656a1a773c9249000000006a47304402201ebad0b1f3a2df05e9368d94a91970334283a5812537e34302260e8b6e124e180220141defc2b70fbd45ac4bb968ce9d51dc219d33709d2cc9fae2c73d61afe9f654012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02244e0000000000001976a9140a5d65dba28a8a9b50b2f0d50da31f24990856fb88ace3b180290a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888aca2dc0a00" // ktlint-disable max-line-length
        notSignUpTx = Transaction(networkParams, Utils.HEX.decode(notSignUpData))
        assertFalse("Tx matches but should not", filter.matches(notSignUpTx))
    }

    @Test
    fun withdrawalReceived_correctMatch() {
        val filter = CrowdNodeWithdrawalReceivedTx(networkParams)
        assertFalse("Tx matches but should not", filter.matches(signUpRequestTx))
        assertFalse("Tx matches but should not", filter.matches(welcomeResponseTx))
        assertTrue("Tx doesn't match", filter.matches(receivedTx))

        val receivedData = "02000000016041b60434fd4353aad02bd3e8067f67b5cb26751c93154bedab79d65e2826c20100000069463043021f7ac8a5f56eb3ec847f891e20799e408d2079f4e671dff5383e688bf86d543e0220412ba4935e84ec7f5d90850aecc5b447b3f6bed82615bba0af52b6d29637cb28012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02e5250000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac13548d180c0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acb4de0a00" // ktlint-disable max-line-length
        val receivedTx = Transaction(networkParams, Utils.HEX.decode(receivedData))
        val connectedData = "0200000002b069bf6ca5f28203f090396e23da59d271397c8978da359362bc6e38990a2631010000006a47304402204c31b657e983e3ea4abbb362fa5fd050085e92cffb723a12c714a625e87d6a0e0220299bf472f12eb54627339ab6b720aed1acc4f238790441deefc29d0ec44f3e10012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff472666d8c072985cb6bbf5ed749ee61d484e080e39817f03f33939f1697b449f000000006b483045022100ed26d531cf0ec33e36e086abfd3b6abad1bbb856b57298d7606bb24db6dbb37002203017a9a7b9ba93606b1047719616adc72456d24cd4ad1e50dee10c9cc961e2d8012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02304e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888acef7a8d180c0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac86de0a00" // ktlint-disable max-line-length
        val connectedTx = Transaction(networkParams, Utils.HEX.decode(connectedData))
        receivedTx.inputs[0].connect(connectedTx.outputs[0])

        assertTrue("Tx doesn't match", filter.matches(receivedTx))
    }

    @Test
    fun depositTx_correctMatch() {
        val filter = CrowdNodeDepositTx(Address.fromBase58(networkParams, "yihMSMoesHX1JhbntTiV5Nptf5NLrmFMCu"))

        assertFalse("Tx matches but should not", filter.matches(signUpRequestTx))
        assertFalse("Tx matches but should not", filter.matches(welcomeResponseTx))
        assertFalse("Tx matches but should not", filter.matches(receivedTx))

        val depositData = "010000000380171b155fae80a1015407f0ba29a40006ff2fbd2e81d71969e865610d36b05e010000006a473044022029b38ae385dfc481de21f82f96d528d2da08927e6a779a7d518b05ca47cd707a02205ddfeae5953695550acf017efca2338d9aa1caedf32960b7f82bf34bf99b8646012103691a23ea571114b17de25c310e2b5f978551e1547d1fa465fdb6bb72d17ba3adffffffff558815361e94ba890231e567190dd10ff599dc5d51d4893a1a81b71da6223576000000006a473044022065be5bfd08ee9960be4c1ce82e951432c2437e76291ec4c18cfb971203befc4e02202f5fdda35868916b0ce7f297e7e6605ccfbee83e0d188e35a76b2dd6dc8e9fcd012103691a23ea571114b17de25c310e2b5f978551e1547d1fa465fdb6bb72d17ba3adffffffff6e413a0400d210f391582b050fbbfea66472e705d6a9c6dbcbb25038bd06428b000000006b483045022100d2b7de552ca945e13b8754ab51499ce732f94cf9b5f4d1730d18270d91d648bc022065ff38acb907f0580a0b9eae80e6836e5e7e2e4466b986567f9e1eb8250ca6aa012103691a23ea571114b17de25c310e2b5f978551e1547d1fa465fdb6bb72d17ba3adffffffff0210530000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888acc0d8a700000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac00000000" // ktlint-disable max-line-length
        val dataConnected0 = "0100000001f09160ffa383d3aaf4cd8f8943b4d9050132448a4ba718d0462238456fd60535010000006a47304402200af78214fe9a60a8615080f3aef89b0a6129bbb5ca70a7c843fb4dd87a2d6b44022056bec0e4279377f82deed94e28607c75a4cf029c01c00506fb5226a9a3d5a164012103cc0bd3cb81e08fd09ee2a6f407dbe4020f12579f8197818d189b6c41318564ecffffffff02865d2d00000000001976a914e1cd0e852b0ea6a9b7d9c58881a4b3ff3842506d88aca8dca700000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac00000000" // ktlint-disable max-line-length
        val dataConnected1 = "02000000026e413a0400d210f391582b050fbbfea66472e705d6a9c6dbcbb25038bd06428b010000006a47304402205e703cd097bb50aedb0013f4ed60cb7a4fb928414480d56121e93e5ee943f7890220711a681aa134a5385fe3692411a2f6ae447715f171e38f3e083e2090899e55b8012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff79ac9f85e66f787269d85dcafb4b7a132d04d8476f15432523adf3f3bbf851e1010000006a47304402205fb26b67d38ea82667cad0b7e8c5b6b8dc5244446326c2aed8b26c769eb9f46202206f88386025cf9cb30bdfdca20b9881db0751e19bbc9ec047fc674ced83e4656f012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02304e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac905c2309010000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac50df0a00" // ktlint-disable max-line-length
        val dataConnected2 = "01000000019858b2a28ca42a04e315cbede431104bef6060b5a7c6d526461f76e7a8f9ae2c010000006a47304402204629c835770e35d836fea256bf70addc680445e75161eee1662deea2ac3081910220230d9d573fedf568dd40badcec74890d54de88b9d3f7710572138425441fab55012103691a23ea571114b17de25c310e2b5f978551e1547d1fa465fdb6bb72d17ba3adffffffff0205030000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac08520000000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac00000000" // ktlint-disable max-line-length
        val depositTx = Transaction(networkParams, Utils.HEX.decode(depositData))
        val depositConnectedTx0 = Transaction(networkParams, Utils.HEX.decode(dataConnected0))
        depositTx.inputs[0].connect(depositConnectedTx0.outputs[0])
        val depositConnectedTx1 = Transaction(networkParams, Utils.HEX.decode(dataConnected1))
        depositTx.inputs[1].connect(depositConnectedTx1.outputs[0])
        val depositConnectedTx2 = Transaction(networkParams, Utils.HEX.decode(dataConnected2))
        depositTx.inputs[2].connect(depositConnectedTx2.outputs[0])

        assertTrue("Tx doesn't match", filter.matches(depositTx))
    }

    @Test
    fun acceptTermsTxFilter_correctMatch() {
        val filter = CrowdNodeAcceptTermsTx(networkParams)

        assertTrue("AcceptTerms tx doesn't match", filter.matches(acceptTermsRequestTx))
        assertEquals("yeZGeMwoGgqseGVnxDbrce4sSpzFsbQf7k", filter.fromAddresses.first().toBase58())

        var notAcceptTermsData = "01000000033f90cbc2d751c77358b3ff37efd72936b389a17b9ec72bdec4678394814cfe2d000000006a473044022050d2f3b6f097f1973b29bb5a0e98f307f6fc338bb8d29e4a7eb257eebd147ccd022055f88aa06cf90aec97991db9c351fd622fa60fe2cb6bbe6df2ecfef03ca047fa012102d336120a91d7d3497056715f6078e36c56e84c41038cf630260ef3245f6ba39effffffff94cae0fa480e004218a66ea7eae8c0a1a39dbd8ebba966004ddfdcac1e11f089000000006b483045022100ed1fbe54b90c8d69e616b79ba5e03e192bdee6b26f66d40d9da14ae7c7e64a9c022062c54fb1635937a38f3b43b504777c9faf357734cad6f53130870f7e980a3be60121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff3e2611f35c7a2fefadce6b115ce8e14b31b627667af9c04909c0ddcceb8294a3000000006a473044022036bed2e8600ed1a715618ca398553254c14fcea824b77ed784cee5f5b23b84df022041c4821e6e639169ddc891e4d6b4e146e5f4684e5687daf5fcce2fd1f73392230121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff0260182300000000001976a9140205411ec940f9139ea72e3a999d21fceff671e688ac4dc27200000000001976a91425b2b9126bf32e6115a813d019e72b7b9106211b88ac00000000" // ktlint-disable max-line-length
        var notAcceptTermsTx = Transaction(networkParams, Utils.HEX.decode(notAcceptTermsData))
        assertEquals(
            notAcceptTermsTx.txId.toString(),
            "ceb0e5920ade494bb4f08f62f9c059c57a60841a9ef8b968e7dde247eb10f9e2"
        )
        assertFalse("Tx matches but should not", filter.matches(notAcceptTermsTx))

        notAcceptTermsData = "02000000024b86656e0590d048c666970225930d5806f746646eea0982be81fb354114e60d010000006a4730440220318c122e24d780b6123f001eb7fb006eda71a17067f25c96f067261da2fab4290220351d6a75c278d780550f0a5494082c749ed7737b4905c4383a206154ab4b7f94012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff476e14bb4fa20abc1fd23ef0ad17c2b65a6cf8959f51cfc412656a1a773c9249000000006a47304402201ebad0b1f3a2df05e9368d94a91970334283a5812537e34302260e8b6e124e180220141defc2b70fbd45ac4bb968ce9d51dc219d33709d2cc9fae2c73d61afe9f654012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02244e0000000000001976a9140a5d65dba28a8a9b50b2f0d50da31f24990856fb88ace3b180290a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888aca2dc0a00" // ktlint-disable max-line-length
        notAcceptTermsTx = Transaction(networkParams, Utils.HEX.decode(notAcceptTermsData))
        assertFalse("Tx matches but should not", filter.matches(notAcceptTermsTx))
    }

    @Test
    fun topUpTxFilter_correctMatch() {
        val transactionBag = mock<TransactionBag> {
            on { isPubKeyHashMine(any(), any()) } doReturn true
        }
        val filter = CrowdNodeTopUpTx(
            Address.fromBase58(networkParams, "yT5rvr43KgBt2R6opiNznRTajFk2u1Lr2o"),
            transactionBag
        )
        assertFalse("Tx matches but should not", filter.matches(receivedTx))

        val topUpData = "0100000001fcb93a5a93588ece9b4b9b8ece83a7afa4e9d2ffd5da0b76ed30c8dff07498ba000000006a47304402203c0226cb59e0b512cea751cf0d44a52a1bb07c9628b26a58221d27665a48eccf0220107831d8e72ef41822bfaac8f5082a18e575f178fa581cc310c9591aefea8dd90121028b14bfe13b4e77af8d2b7e1da61b034bec0e36f9fd4ddea2c02537027ddec68dffffffff02bd850100000000001976a9148b6743bde3b5b5778220891e8572d2475c1c9e0d88aca0bb0d00000000001976a9144a37287587b5c58c704ccdee322ab43521d3ecd288ac00000000" // ktlint-disable max-line-length
        val topUpTx = Transaction(networkParams, Utils.HEX.decode(topUpData))
        val connectedData = "0100000001e0ab1c7b601baebdccf03bc55787ad3957d8d13ba9a0a5e4d39c161302194aad000000006a473044022074266bfc5df38745604753ee0a48fa9ed7729c305b97a8816fa1c286da53bf0c02204ae1b458a3a956b8ad1a20c8c4f6c48dfe4ef05169afa77106a1816d07fb5c030121027c974ed291479646948719c1889aee27bc4e29af2a6c7236a92555d2b3348b97ffffffff0240420f00000000001976a91428fd6a3abc9633389c146b44f59243ac1ec3caac88ac629cd223000000001976a9140817e5a5adce5731e83f318fb725bd0e339effef88ac00000000" // ktlint-disable max-line-length
        val connectedTx = Transaction(networkParams, Utils.HEX.decode(connectedData))
        val spentByData = "0100000001728d9b2f7080e4d404a0d5f1d6f1a1e5e8c08441934d65ceb70c8a0082f006da010000006a473044022054401d60d62e97d7f4ab5e1c826d520dde0282d8519806ff9ad42642dc12930002200e9d850b926191662d6faf3c9d01489d037710d3f43d1f01063dabfa31b4beec012102c9ec4d5cd8547b6811c0ecb9f18a6970443fa103ff30846ec369b5bf06a252a3ffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a9144a37287587b5c58c704ccdee322ab43521d3ecd288ac00000000" // ktlint-disable max-line-length
        val spentByTx = Transaction(networkParams, Utils.HEX.decode(spentByData))
        topUpTx.inputs[0].connect(connectedTx.outputs[0])
        topUpTx.outputs[1].markAsSpent(spentByTx.inputs[0])

        assertTrue("TopUp Tx does not match", filter.matches(topUpTx))
    }

    @Test
    fun pleaseAcceptTermsTxFilter_correctMatch() {
        val txData = "02000000042607763cf6eceb2478060ead38fbb3151b7676b6a243e78b58c420a4ad99cb05010000006a47304402201f95f3a194bd51c521adcd46173d3d5c9bd2dd148004dd1da72e686fd6d946e4022020e34d85cd817aff0663b133915ca2eda5ecd5d5a93fba33f2e9644f1d1513a3012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffffe27ecbb210e98a5d2dba6e3bfa0732b8f6371155c3f8bd0420027d2eb3d24a7d010000006b483045022100c7d5c710ebdf8a2526389347823c3de83b3da498eeac5d1e9001e2e86f4cd0d002200e91ee98abc4f5fb5a78e8e80ed6fd17697a706e7118f87e545d8fdad65a845b012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff70a65da4b8d4438058c2e8f36811577cdb244d33c7973644386259135e3635a3010000006b483045022100d1c279574bdb0a4c72b6a11247f2945746b50f3a847c9c6925f0badfa8f5827a0220059884f1e9099fcfbb4966cced355e764ddf18bc60a3e03a3804c0c9b20618a4012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff4605e08cc9758029e89705c41872f063854684b5abf2020e56aca53f161b3fea000000006b483045022100f5afc8c1e722b25532b0a3561f0c37cf80bcd288a40fa0ced53d9a137f06dbc8022067c8ad28484b4a504f74cc7ad754ab4b87f0fbb46a4725e915b625eb000be8fd012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a914b889fb3449a36530c85d9689c4773c5cd1ba223388ac51844c8c060000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acb1f80a00" // ktlint-disable max-line-length
        val tx = Transaction(networkParams, Utils.HEX.decode(txData))
        val connectedTxData = "0100000001fc44931460fcb2a3b366f4b967fb4bde573667c6bcee2eaae198e3c8ed1faff5000000006b483045022100832d93353b7651d8bcf38d9d450de4234e9dc3bd243199ab06fa775cc9096c9502200f7d574aaa4b52ac254aeaf372efa7833f245acefb4e9ae2b81a1faeffcd9016012103f5ca44dde27d2a4219ad6e66617ef2bfbeb11021e761e835021e781505650915ffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a914b889fb3449a36530c85d9689c4773c5cd1ba223388ac00000000" // ktlint-disable max-line-length
        val connectedTx = Transaction(networkParams, Utils.HEX.decode(connectedTxData))
        tx.inputs[3].connect(connectedTx.outputs[0])

        val filter = CrowdNodeAcceptTermsResponse(networkParams)
        assertTrue("Transaction doesn't match", filter.matches(tx))
        assertEquals("Wrong TO address", "yd9CUc7wvATUS3GfdmcAhRZhG7719jhNf9", filter.toAddress?.toBase58())

        assertFalse("Tx matches but should not", filter.matches(acceptTermsRequestTx))
    }

    @Test
    fun welcomeToApiTxFilter_correctMatch() {
        val filter = CrowdNodeWelcomeToApiResponse(networkParams)
        assertTrue("Transaction doesn't match", filter.matches(welcomeResponseTx))
        assertEquals("Wrong TO address", "yihMSMoesHX1JhbntTiV5Nptf5NLrmFMCu", filter.toAddress?.toBase58())

        assertFalse("Tx matches but should not", filter.matches(acceptTermsRequestTx))
    }

    @Test
    fun possibleWelcomeAndAcceptTermsFilters_correctMatch() {
        val bagMock = mock<TransactionBag> {
            on { isPubKeyHashMine(any(), any()) } doReturn true
        }

        val possibleAcceptFilter = PossibleAcceptTermsResponse(
            bagMock,
            Address.fromBase58(networkParams, "yVQr2XQ6eWduZmyQgPfQiBA3uwvPaRWpxo")
        )
        var txData = "0200000002c67bbdfacfb02f7729ec60c47b85e20f898871b3b96c5f344180b08e189f9250010000006a47304402200d74f07333ad9bb5fa813ddc9e0082b6cdd8893e7e9ed6fcbf596c9ed238dede02206f5a875334990d29ae8ae3c15f2256f5f236d9ae9e2e10135afc36bfc624b466012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff4749fa6a4c09d2f6091a7cb191c6d2533986e0517991cc68af5ae42ca66e7bf9000000006a47304402207009df8e1e8ad59f44e6dc84c6e99677454d42491907d8d993bc1d1bc3da2ef8022050c09ea66986d61ec813263b71c3dc7df327b24c2c3b9c528b719326cce298e8012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a91463be8f527ae6e7c9ce4148bdfda835074062db2288aca025d9be170000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acab7c0b00" // ktlint-disable max-line-length
        val acceptTx = Transaction(networkParams, Utils.HEX.decode(txData))
        assertTrue("Transaction doesn't match", possibleAcceptFilter.matches(acceptTx))
        assertFalse("Tx matches but should not", possibleAcceptFilter.matches(acceptTermsRequestTx))

        txData = "0200000001b7407f1686f90c705dee1266c60e4174812f3f771b4eb09522591b7e7e284c38010000006a4730440220081ba8f1eaaee49d8e4e7dabac4e222e91cf2125d99ab2f1d6922362f1827e2b0220547d9b0c6d1938172ebbb1dc337f8bfe8425c8cd3238f055f6c48201847b4300012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02244e0000000000001976a91463be8f527ae6e7c9ce4148bdfda835074062db2288acbe1e7ce8190000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acb17d0b00" // ktlint-disable max-line-length
        val welcomeTx = Transaction(networkParams, Utils.HEX.decode(txData))
        val possibleWelcomeFilter = PossibleWelcomeResponse(
            bagMock,
            Address.fromBase58(networkParams, "yVQr2XQ6eWduZmyQgPfQiBA3uwvPaRWpxo")
        )
        assertTrue("Transaction doesn't match", possibleWelcomeFilter.matches(welcomeTx))
        assertFalse("Tx matches but should not", possibleWelcomeFilter.matches(signUpRequestTx))

        bagMock.stub {
            on { isPubKeyHashMine(any(), any()) } doReturn false
        }

        assertFalse("Tx matches but should not", possibleAcceptFilter.matches(acceptTx))
        assertFalse("Tx matches but should not", possibleWelcomeFilter.matches(welcomeTx))
    }

    @Test
    fun internalTxWithAcceptTermsAmount_doesNotMatch() {
        val bagMock = mock<TransactionBag> {
            on { isPubKeyHashMine(any(), any()) } doReturn true
        }

        val connectedData = "0100000001a144eb2405271f2a56332ba40e61c4248a42c45dbc7ca453c5ac6bd1ca13f4ed170000006b483045022100a84e01ee3b13b6056d3a2e3ae2e000aa08897f6b8769c149ae940ad0d348305702207f494646bf8789610d2a44642dbf3136519c93c0a97df885ae8fba81848361850121022bc500272dc2263fda23e4ccd99d39942ad92127e8d43581876038c9b9014517ffffffff03409c0000000000001976a9147b5bea31861a1cfca6cb5cb93277fb7515bda7df88acabc55101000000001976a91486c1305dc7b556051ef2f3c1a7f8671f1abc349988ac8016a437000000001976a914ae0621debab253e792f2558dd1889b5c29110dff88ac00000000" // ktlint-disable max-line-length
        val connectedTx = Transaction(networkParams, Utils.HEX.decode(connectedData))

        val txData = "01000000017f8b38abf42bce8bfbfc9c5965096a9cfed3ef0a1fc7fe8a79881a90a393a790020000006a47304402203545c3a1ee67fef9f4733caa38619affd92e012271b01078a7eb80b179c56355022040ad74e6521c659104aec47621f7c8cf8defd1eb1c3f5484ada3b124cad4454a01210221a2fb697857eeb83d47a57050f27a38c84958ce7b79741d7f686c2867a4a895ffffffff03224e0000000000001976a914197b4429f61bdb64a859567b441a23e6630d533588ac224e0000000000001976a914f850a88d4f515ea7b92908ad4d08e7383922ca0988ac3779a337000000001976a914b658c3dac62e4c570b08cf317fb70436ea37870088ac00000000" // ktlint-disable max-line-length
        val internalTx = Transaction(networkParams, Utils.HEX.decode(txData))
        internalTx.inputs[0].connect(connectedTx.outputs[0])

        val possibleAcceptTermsFilter = PossibleAcceptTermsResponse(bagMock, null)
        assertFalse("Tx matches but should not", possibleAcceptTermsFilter.matches(internalTx))
    }

    @Test
    fun crowdNodeAPIConfirmationForwarded_correctMatch() {
        val txData = "01000000016fdb75611fd8892d8d19707f0d0958da5b930c635750f2c8c5bf5a48458a8ffd000000006b483045022100ff77055377b33afb8fc2f622fda59816394a567c50e98569fe8ab68d797948b802204b05504b3f837e80f4d0d8c3c6d98107e770572a8eaae66ddd14a660fe9674f101210275ab1f1c864e594c5e075ac45fbd01a45285701e429b842f8d0a1c872cf3a7baffffffff0149d00000000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac00000000" // ktlint-disable max-line-length
        val forwardedTx = Transaction(networkParams, Utils.HEX.decode(txData))
        val connected = "01000000017a4c4461b44d14a3866bc89482bdd35c800961879d65fc4e0542a377232f124b000000006a47304402206beee5884010d44501dee9094d9922af0c22b63f3afc1f3aa5c43bfcf59f38070220676e5bbeb712cbbbadbde6959cdee3367d1578bd06204178630cb1ba729073a20121025e25aa5f744bdd42f386a11efc584fa25afdaa299477038d2b86ac655287305effffffff0231d40000000000001976a914dcd48b7080eafd7b4db3bac7ea8480ccc3b1093388ac7d54a750000000001976a9149701a96ac4f8bb1a627be9928ccbc717ccce485788ac00000000" // ktlint-disable max-line-length
        val connectedTx = Transaction(networkParams, Utils.HEX.decode(connected))
        forwardedTx.inputs[0].connect(connectedTx.outputs[0])

        val filter = CrowdNodeAPIConfirmationForwarded(networkParams)
        assertTrue("Transaction doesn't match", filter.matches(forwardedTx))
        assertFalse("Tx matches but should not", filter.matches(signUpRequestTx))
    }
}
