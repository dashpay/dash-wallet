/*
 * Copyright (c) 2022. Dash Core Group.
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

import de.schildbach.wallet.Constants.HEX
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.*
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.transactions.TransactionCategory
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionExportTest {

    @Test
    fun csvExporterTest() {
        val context = Context.getOrCreate(TestNet3Params.get());
        Context.propagate(context)
        val params = TestNet3Params.get()//context.params
        val txData =
            HEX.decode("01000000013511fbb91663e90da67107e1510521440a9bf73878e45549ac169c7cd30c826e010000006a473044022048edae0ab0abcb736ca1a8702c2e99673d7958f4661a4858f437b03a359c0375022023f4a45b8817d9fcdad073cfb43320eae7e064a7873564e4cbc8853da548321a01210359c815be43ce68de8188f02b1b3ecb589fb8facdc2d694104a13bb2a2055f5ceffffffff0240420f00000000001976a9148017fd8d70d8d4b8ddb289bb73bcc0522bc06e0888acb9456900000000001976a914c9e6676121e9f38c7136188301a95d800ceade6588ac00000000");
        val tx = Transaction(params, txData, 0);

        val wallet = Wallet(
            params,
            KeyChainGroup.builder(params).addChain(
                DeterministicKeyChain.builder()
                    .seed(
                        DeterministicSeed(
                            "weapon elder job emotion aunt include deer owner salon census half divide",
                            null,
                            "",
                            System.currentTimeMillis() / 1000
                        )
                    )
                    .accountPath(DeterministicKeyChain.BIP44_ACCOUNT_ZERO_PATH_TESTNET)
                    .outputScriptType(Script.ScriptType.P2PKH)
                    .build()
            ).build()
        )
        // create enough addresses to capture the transactions
        for (i in 0..10) {
            wallet.activeKeyChain.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS)
            wallet.activeKeyChain.getKey(KeyChain.KeyPurpose.CHANGE)
        }

        wallet.addWalletTransaction(WalletTransaction(WalletTransaction.Pool.UNSPENT, tx))
        val txMetadata = TransactionMetadata(
            tx.txId,
            tx.updateTime?.time ?: System.currentTimeMillis(),
            tx.outputSum,
            TransactionCategory.fromTransaction(tx.type, tx.outputSum, tx.isEntirelySelf(wallet)),
            TaxCategory.TransferIn
        )
        val transactionMetadataProvider = mockk<TransactionMetadataProvider>()
        coEvery { transactionMetadataProvider.getAllTransactionMetadata() } returns listOf(txMetadata)

        val exporter = TaxBitExporter(transactionMetadataProvider, wallet)

        runBlocking {
            val csvString = exporter.exportString()
            val csvLines = csvString.split("\n")

            assertEquals(
                "Date and Time,Transaction Type,Sent Quantity,Sent Currency,Sending Source,Received Quantity,Received Currency,Receiving Destination,Fee,Fee Currency,Exchange Transaction ID,Blockchain Transaction Hash",
                csvLines[0]
            )
            assertEquals(
                "1970-01-01T00:00:00Z,Transfer-in,,DASH,DASH Wallet,0.01,DASH,DASH Wallet,,,,dd07f79a86185f3b5eeb2b19bdbc1d97eb9db0b887dcedb0fc30c622c43a27ba",
                csvLines[1]
            )
        }
    }
}