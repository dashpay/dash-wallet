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

import junit.framework.TestCase
import org.bitcoinj.core.*
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.integrations.crowdnode.transactions.CrowdNodeSignUpTx
import org.dashj.bls.Utils
import org.junit.Test

class CrowdNodeTxFilterTest {
    @Test
    fun signUpTxFilter_correctMatch() {
        val networkParams = TestNet3Params.get()
        var notSignUpData = "01000000033f90cbc2d751c77358b3ff37efd72936b389a17b9ec72bdec4678394814cfe2d000000006a473044022050d2f3b6f097f1973b29bb5a0e98f307f6fc338bb8d29e4a7eb257eebd147ccd022055f88aa06cf90aec97991db9c351fd622fa60fe2cb6bbe6df2ecfef03ca047fa012102d336120a91d7d3497056715f6078e36c56e84c41038cf630260ef3245f6ba39effffffff94cae0fa480e004218a66ea7eae8c0a1a39dbd8ebba966004ddfdcac1e11f089000000006b483045022100ed1fbe54b90c8d69e616b79ba5e03e192bdee6b26f66d40d9da14ae7c7e64a9c022062c54fb1635937a38f3b43b504777c9faf357734cad6f53130870f7e980a3be60121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff3e2611f35c7a2fefadce6b115ce8e14b31b627667af9c04909c0ddcceb8294a3000000006a473044022036bed2e8600ed1a715618ca398553254c14fcea824b77ed784cee5f5b23b84df022041c4821e6e639169ddc891e4d6b4e146e5f4684e5687daf5fcce2fd1f73392230121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff0260182300000000001976a9140205411ec940f9139ea72e3a999d21fceff671e688ac4dc27200000000001976a91425b2b9126bf32e6115a813d019e72b7b9106211b88ac00000000"
        var notSignUpTx = Transaction(networkParams, Utils.HEX.decode(notSignUpData))

        TestCase.assertEquals(
            notSignUpTx.txId.toString(),
            "ceb0e5920ade494bb4f08f62f9c059c57a60841a9ef8b968e7dde247eb10f9e2"
        )

        val filter = CrowdNodeSignUpTx(networkParams)
        TestCase.assertFalse("Tx matches but should not", filter.matches(notSignUpTx))

        val signUpData = "0100000001f15743be9d858f7e6213bca8262bda38b9b59d44747051899533764cd3ca6606000000006a4730440220412fb2a56090bc271d25fd66a095749bc8c9d4b8200716ed452e91870dddc43702206c3b42a0f3ef11da3a563960c9ac208b6d4da02bafecb06eb7a28d29f8b7cb0f012103691a23ea571114b17de25c310e2b5f978551e1547d1fa465fdb6bb72d17ba3adffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac00000000"
        val signUpTx = Transaction(networkParams, Utils.HEX.decode(signUpData))

        val connected = "0100000001a14301088210333bd5d0959624b153d6d0dcfd5a67813ec11df4879808b1b6ac000000006a473044022000c5bb339303d916de5765446a93a1513d151407c598f0f7aba79ea62795892b022012f97f16d00876b08d8e55a8c99ad4b009615789c6e4322cdb760f4a649d3ed5012103626557ad8e11b2bf7004683e2bd634579329b00902e67177c768bfa76fc3796bffffffff02a0bb0d00000000001976a914f57766c540e7e165092e739e115383bd04d2c21888acfdd98a00000000001976a914bd065b89a786f96a4529a4dacc0bb4a14268147088ac00000000"
        val connectedTx = Transaction(networkParams, Utils.HEX.decode(connected))
        signUpTx.inputs[0].connect(connectedTx.outputs[0])

        TestCase.assertTrue("SignUp tx doesn't match", filter.matches(signUpTx))
        TestCase.assertEquals(1, filter.fromAddresses.size)
        TestCase.assertEquals("yihMSMoesHX1JhbntTiV5Nptf5NLrmFMCu", filter.fromAddresses.first().toBase58())

        notSignUpData = "02000000024b86656e0590d048c666970225930d5806f746646eea0982be81fb354114e60d010000006a4730440220318c122e24d780b6123f001eb7fb006eda71a17067f25c96f067261da2fab4290220351d6a75c278d780550f0a5494082c749ed7737b4905c4383a206154ab4b7f94012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff476e14bb4fa20abc1fd23ef0ad17c2b65a6cf8959f51cfc412656a1a773c9249000000006a47304402201ebad0b1f3a2df05e9368d94a91970334283a5812537e34302260e8b6e124e180220141defc2b70fbd45ac4bb968ce9d51dc219d33709d2cc9fae2c73d61afe9f654012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02244e0000000000001976a9140a5d65dba28a8a9b50b2f0d50da31f24990856fb88ace3b180290a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888aca2dc0a00"
        notSignUpTx = Transaction(networkParams, Utils.HEX.decode(notSignUpData))

        TestCase.assertFalse("Tx matches but should not", filter.matches(notSignUpTx))
    }
}