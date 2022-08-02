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

package org.dash.wallet.common

import junit.framework.TestCase.*
import org.bitcoinj.core.*
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.transactions.filters.CoinsFromAddressTxFilter
import org.dash.wallet.common.transactions.filters.CoinsToAddressTxFilter
import org.dashj.bls.Utils
import org.junit.Test

class TransactionFilterTests {
    @Test
    fun coinsToAddressTxFilter_correctMatch() {
        val networkParams = TestNet3Params.get()
        var txData = "01000000033f90cbc2d751c77358b3ff37efd72936b389a17b9ec72bdec4678394814cfe2d000000006a473044022050d2f3b6f097f1973b29bb5a0e98f307f6fc338bb8d29e4a7eb257eebd147ccd022055f88aa06cf90aec97991db9c351fd622fa60fe2cb6bbe6df2ecfef03ca047fa012102d336120a91d7d3497056715f6078e36c56e84c41038cf630260ef3245f6ba39effffffff94cae0fa480e004218a66ea7eae8c0a1a39dbd8ebba966004ddfdcac1e11f089000000006b483045022100ed1fbe54b90c8d69e616b79ba5e03e192bdee6b26f66d40d9da14ae7c7e64a9c022062c54fb1635937a38f3b43b504777c9faf357734cad6f53130870f7e980a3be60121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff3e2611f35c7a2fefadce6b115ce8e14b31b627667af9c04909c0ddcceb8294a3000000006a473044022036bed2e8600ed1a715618ca398553254c14fcea824b77ed784cee5f5b23b84df022041c4821e6e639169ddc891e4d6b4e146e5f4684e5687daf5fcce2fd1f73392230121037c4c4205eceb06bbf1e4894e52ecddcf700e1a699e2a4cbee9fd7ed748fb7a59ffffffff0260182300000000001976a9140205411ec940f9139ea72e3a999d21fceff671e688ac4dc27200000000001976a91425b2b9126bf32e6115a813d019e72b7b9106211b88ac00000000"
        var tx = Transaction(networkParams, Utils.HEX.decode(txData))

        assertEquals(tx.txId.toString(), "ceb0e5920ade494bb4f08f62f9c059c57a60841a9ef8b968e7dde247eb10f9e2")

        var filter = CoinsToAddressTxFilter(
            Address.fromBase58(networkParams, "yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi"),
            Coin.parseCoin("0.023")
        )
        assertTrue("Transaction doesn't match", filter.matches(tx))

        txData = "010000000188f39bfd0f75e6d4ecebe2b3efeb9da2549e374405a5b03a1c2f9cbee57c2616000000006a47304402205acbc432ec1a75922f18d8323ec224f8b0e41cd1ecc14c9b803ccb88ea3f687e022013731b89396db78550dee85c3e81fe5de75209a8064e394de12af4ba42e6650801210204d4222b4b0f992567fce5432f01085d2d7c62ee9a0fe61476429584290c164fffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a91486086148698d4cef518ec573fed2b39d4477b63988ac00000000"
        tx = Transaction(networkParams, Utils.HEX.decode(txData))
        filter = CoinsToAddressTxFilter(
            Address.fromBase58(networkParams, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"),
            Coin.valueOf(151072)
        )

        assertTrue("Transaction doesn't match", filter.matches(tx))

        txData = "02000000024b86656e0590d048c666970225930d5806f746646eea0982be81fb354114e60d010000006a4730440220318c122e24d780b6123f001eb7fb006eda71a17067f25c96f067261da2fab4290220351d6a75c278d780550f0a5494082c749ed7737b4905c4383a206154ab4b7f94012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff476e14bb4fa20abc1fd23ef0ad17c2b65a6cf8959f51cfc412656a1a773c9249000000006a47304402201ebad0b1f3a2df05e9368d94a91970334283a5812537e34302260e8b6e124e180220141defc2b70fbd45ac4bb968ce9d51dc219d33709d2cc9fae2c73d61afe9f654012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02244e0000000000001976a9140a5d65dba28a8a9b50b2f0d50da31f24990856fb88ace3b180290a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888aca2dc0a00"
        tx = Transaction(networkParams, Utils.HEX.decode(txData))
        filter = CoinsToAddressTxFilter(
            Address.fromBase58(networkParams, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"),
            Coin.valueOf(20004)
        )

        assertFalse("Transaction match but should not", filter.matches(tx))
    }

    @Test
    fun coinsFromAddressTxFilter_correctMatch() {
        val networkParams = TestNet3Params.get()
        var txData = "02000000042607763cf6eceb2478060ead38fbb3151b7676b6a243e78b58c420a4ad99cb05010000006a47304402201f95f3a194bd51c521adcd46173d3d5c9bd2dd148004dd1da72e686fd6d946e4022020e34d85cd817aff0663b133915ca2eda5ecd5d5a93fba33f2e9644f1d1513a3012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffffe27ecbb210e98a5d2dba6e3bfa0732b8f6371155c3f8bd0420027d2eb3d24a7d010000006b483045022100c7d5c710ebdf8a2526389347823c3de83b3da498eeac5d1e9001e2e86f4cd0d002200e91ee98abc4f5fb5a78e8e80ed6fd17697a706e7118f87e545d8fdad65a845b012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff70a65da4b8d4438058c2e8f36811577cdb244d33c7973644386259135e3635a3010000006b483045022100d1c279574bdb0a4c72b6a11247f2945746b50f3a847c9c6925f0badfa8f5827a0220059884f1e9099fcfbb4966cced355e764ddf18bc60a3e03a3804c0c9b20618a4012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff4605e08cc9758029e89705c41872f063854684b5abf2020e56aca53f161b3fea000000006b483045022100f5afc8c1e722b25532b0a3561f0c37cf80bcd288a40fa0ced53d9a137f06dbc8022067c8ad28484b4a504f74cc7ad754ab4b87f0fbb46a4725e915b625eb000be8fd012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a914b889fb3449a36530c85d9689c4773c5cd1ba223388ac51844c8c060000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acb1f80a00"
        var tx = Transaction(networkParams, Utils.HEX.decode(txData))
        var connectedTxData = "0100000001fc44931460fcb2a3b366f4b967fb4bde573667c6bcee2eaae198e3c8ed1faff5000000006b483045022100832d93353b7651d8bcf38d9d450de4234e9dc3bd243199ab06fa775cc9096c9502200f7d574aaa4b52ac254aeaf372efa7833f245acefb4e9ae2b81a1faeffcd9016012103f5ca44dde27d2a4219ad6e66617ef2bfbeb11021e761e835021e781505650915ffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a914b889fb3449a36530c85d9689c4773c5cd1ba223388ac00000000"
        var connectedTx = Transaction(networkParams, Utils.HEX.decode(connectedTxData))
        tx.inputs[3].connect(connectedTx.outputs[0])

        var filter = CoinsFromAddressTxFilter(
            Address.fromBase58(networkParams, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"),
            Coin.valueOf(20002)
        )
        assertTrue("Transaction doesn't match", filter.matches(tx))
        assertEquals("Wrong TO address", "yd9CUc7wvATUS3GfdmcAhRZhG7719jhNf9", filter.toAddress?.toBase58())

        txData = "01000000015278aa3819d5663c38e83d98831131cbf4613028710e0807f1f3cc2c267a1d1c010000006a47304402201b32c065edb60618307f14e5f065b5ca12a9f77bfabc31bd60304730217b2cb10220135b503243dfb9a5a3e40b6b7049d6a94fcf6fb596ca8b1cd08e0389d38907920121034fc8962de581ea8bb89fd364f9560e5e3646744a7ce4ec45feade07135096997ffffffff0231d40000000000001976a9146788908cc67f13b9636fa1c50a18634dc7faf70288ac58e9da14000000001976a91497dcc58e0f473cca0318eaaa4d305c8b5d06f0b688ac00000000"
        tx = Transaction(networkParams, Utils.HEX.decode(txData))
        connectedTxData = "01000000012fbfc99b81cae50e408e65978450c4176dac1abd79a750dcf7f197ae64953c8a010000006b483045022100f8b72d1516843ae7e2ce8667c53811b2c9d6eeeec74f30817ec61a08380f7e5802203b08f11294136baae1208baa704a03251d9965817beac69d5406f14c3e5624aa012102ff263761f3c8bb1a71a71faa5e9d930a28ff379f3f0b3c33be0bd41523d4b457ffffffff0231d40000000000001976a9140b627cdc0c1dc9f0a87c9491a18471e88dbfef2588ac6cbedb14000000001976a91485d306c762871bfb5572908b23dc9294ad14c44a88ac00000000"
        connectedTx = Transaction(networkParams, Utils.HEX.decode(connectedTxData))
        tx.inputs[0].connect(connectedTx.outputs[0])

        filter = CoinsFromAddressTxFilter(
            Address.fromBase58(networkParams, "yYX3X6NMk5yChbWUzivYvXjvDRHzF9ojgs"),
            Coin.valueOf(54321)
        )

        assertTrue("Transaction doesn't match", filter.matches(tx))
        assertEquals("Wrong TO address", "yVkt3e49pAj11jSj4HAnzVAWmy4VD1MwZd", filter.toAddress?.toBase58())

        txData = "0100000001f15743be9d858f7e6213bca8262bda38b9b59d44747051899533764cd3ca6606000000006a4730440220412fb2a56090bc271d25fd66a095749bc8c9d4b8200716ed452e91870dddc43702206c3b42a0f3ef11da3a563960c9ac208b6d4da02bafecb06eb7a28d29f8b7cb0f012103691a23ea571114b17de25c310e2b5f978551e1547d1fa465fdb6bb72d17ba3adffffffff02204e0200000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac9d6c0b00000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac00000000"
        tx = Transaction(networkParams, Utils.HEX.decode(txData))
        connectedTxData = "0100000001a14301088210333bd5d0959624b153d6d0dcfd5a67813ec11df4879808b1b6ac000000006a473044022000c5bb339303d916de5765446a93a1513d151407c598f0f7aba79ea62795892b022012f97f16d00876b08d8e55a8c99ad4b009615789c6e4322cdb760f4a649d3ed5012103626557ad8e11b2bf7004683e2bd634579329b00902e67177c768bfa76fc3796bffffffff02a0bb0d00000000001976a914f57766c540e7e165092e739e115383bd04d2c21888acfdd98a00000000001976a914bd065b89a786f96a4529a4dacc0bb4a14268147088ac00000000"
        connectedTx = Transaction(networkParams, Utils.HEX.decode(connectedTxData))
        tx.inputs[0].connect(connectedTx.outputs[0])

        assertFalse("Transaction matches but shouldn't", filter.matches(tx))
    }
}