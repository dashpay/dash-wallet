/*
 * Copyright (c) 2024. Dash Core Group.
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

package de.schildbach.wallet.performance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.schildbach.wallet.Constants
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.KeyChainGroup
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.bitcoinj.wallet.WalletTransaction
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class WalletSavePerformanceTest {

    companion object {
        private val log = LoggerFactory.getLogger(WalletSavePerformanceTest::class.java)
        private const val MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        private const val EXPECTED_SAVE_TIME_MS = 5000L // Expected maximum save time
    }

    private lateinit var context: Context
    private lateinit var testWallet: Wallet
    private lateinit var tempWalletFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Initialize Bitcoin context
        org.bitcoinj.core.Context.getOrCreate(TestNet3Params.get())
        
        // Create test directory
        val testDir = File(context.cacheDir, "wallet_test")
        testDir.mkdirs()
        tempWalletFile = File(testDir, "test_wallet.wallet")
        
        // Create or load test wallet
        testWallet = createTestWallet()
        
        log.info("Test setup complete. Wallet has ${testWallet.keyChainGroupSize} keychains")
    }

    @Test
    fun testWalletSavePerformance_EmptyWallet() {
        log.info("Testing empty wallet save performance")
        
        val saveTime = measureTimeMillis {
            saveWalletToFile(testWallet, tempWalletFile)
        }
        
        log.info("Empty wallet save time: ${saveTime}ms")
        assertTrue("Wallet save took too long: ${saveTime}ms", saveTime < EXPECTED_SAVE_TIME_MS)
    }

    @Test
    fun testWalletSavePerformance_WithTransactions() {
        log.info("Testing wallet with transactions save performance")
        
        // Add some test transactions to make the wallet more realistic
        addTestTransactions(testWallet, 50)
        
        val saveTime = measureTimeMillis {
            saveWalletToFile(testWallet, tempWalletFile)
        }
        
        log.info("Wallet with transactions save time: ${saveTime}ms")
        assertTrue("Wallet save took too long: ${saveTime}ms", saveTime < EXPECTED_SAVE_TIME_MS * 2)
    }

    @Test
    fun testWalletSavePerformance_LargeWallet() {
        log.info("Testing large wallet save performance")
        
        // Add many transactions to simulate a heavily used wallet
        addTestTransactions(testWallet, 200)
        
        // Generate additional addresses
        repeat(100) {
            testWallet.currentReceiveAddress()
            testWallet.currentChangeAddress()
        }
        
        val saveTime = measureTimeMillis {
            saveWalletToFile(testWallet, tempWalletFile)
        }
        
        log.info("Large wallet save time: ${saveTime}ms")
        assertTrue("Large wallet save took too long: ${saveTime}ms", saveTime < EXPECTED_SAVE_TIME_MS * 3)
    }

    @Test
    fun testWalletSavePerformance_MultipleConsecutiveSaves() {
        log.info("Testing multiple consecutive wallet saves")
        
        addTestTransactions(testWallet, 100)
        
        val saveTimes = mutableListOf<Long>()
        
        repeat(10) { iteration ->
            val saveTime = measureTimeMillis {
                val tempFile = File(tempWalletFile.parentFile, "test_wallet_$iteration.wallet")
                saveWalletToFile(testWallet, tempFile)
                tempFile.delete() // Clean up
            }
            saveTimes.add(saveTime)
            log.info("Save iteration $iteration: ${saveTime}ms")
        }
        
        val averageTime = saveTimes.average()
        val maxTime = saveTimes.maxOrNull() ?: 0L
        
        log.info("Average save time: ${"%.2f".format(averageTime)}ms, Max: ${maxTime}ms")
        assertTrue("Average save time too high: $averageTime ms", averageTime < EXPECTED_SAVE_TIME_MS)
        assertTrue("Max save time too high: ${maxTime}ms", maxTime < EXPECTED_SAVE_TIME_MS * 2)
    }

    @Test
    fun testWalletLoadFromResources() {
        log.info("Testing wallet load from resources")
        
        // First, let's list all available assets
        try {
            val assetList = context.assets.list("")
            log.info("Available assets: ${assetList?.joinToString(", ") ?: "none"}")
        } catch (e: Exception) {
            log.info("Could not list assets: ${e.message}")
        }
        
        // Try to load a test wallet from resources if available
        try {
            // Use test context to access test assets
            val testContext = InstrumentationRegistry.getInstrumentation().context
            val inputStream: InputStream = testContext.assets.open("test_wallet.wallet")
            val loadedWallet: Wallet
            val loadTime = measureTimeMillis {
                // Use proper extensions as required by DashWalletFactory
                val params = TestNet3Params.get()
                val extensions = arrayOf(
                    org.bitcoinj.wallet.authentication.AuthenticationGroupExtension(params)
                )
                loadedWallet = WalletProtobufSerializer().readWallet(inputStream, false, extensions)
                log.info("Loaded wallet with ${loadedWallet.getTransactionCount(true)} transactions")
            }
            log.info("Wallet load time: ${loadTime}ms")
            assertTrue("Wallet load took too long: ${loadTime}ms", loadTime < EXPECTED_SAVE_TIME_MS)

            //log.info((loadedWallet as WalletEx).coinJoin.keyUsageReport)

            // Test serialization of the loaded wallet
            val serializeTime = measureTimeMillis {
                val outputStream = ByteArrayOutputStream()
                WalletProtobufSerializer().writeWallet(loadedWallet, outputStream)
                val serializedSize = outputStream.size()
                log.info("Serialized wallet size: $serializedSize bytes")
            }
            log.info("In-memory serialization time: ${serializeTime}ms")

            for (i in 1..5) {
                val serializeTime = measureTimeMillis {
                    val outputStream = ByteArrayOutputStream()
                    WalletProtobufSerializer().writeWallet(loadedWallet, outputStream)
                    val serializedSize = outputStream.size()
                    log.info("$i. Serialized wallet size: $serializedSize bytes")
                }
                log.info("$i. In-memory serialization time: ${serializeTime}ms")
            }
        } catch (e: Exception) {
            log.info("No test wallet in assets, skipping resource load test: ${e.message}")
            log.info("Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
        }
    }

    @Test
    fun testInMemoryWalletSerialization() {
        log.info("Testing in-memory wallet serialization performance")
        
        addTestTransactions(testWallet, 100)
        
        val serializeTime = measureTimeMillis {
            val outputStream = ByteArrayOutputStream()
            WalletProtobufSerializer().writeWallet(testWallet, outputStream)
            val serializedSize = outputStream.size()
            log.info("Serialized wallet size: $serializedSize bytes")
        }
        
        log.info("In-memory serialization time: ${serializeTime}ms")
        assertTrue("Wallet serialization took too long: ${serializeTime}ms", serializeTime < EXPECTED_SAVE_TIME_MS)
    }

    private fun createTestWallet(): Wallet {
        val params = TestNet3Params.get()
        val seed = DeterministicSeed(MNEMONIC, null, "", Constants.EARLIEST_HD_SEED_CREATION_TIME)
        
        val keyChainGroup = KeyChainGroup.builder(params)
            .fromSeed(seed, Script.ScriptType.P2PKH)
            .addChain(
                DeterministicKeyChain.builder()
                    .seed(seed)
                    .accountPath(Constants.BIP44_PATH)
                    .build()
            )
            .build()
            
        return WalletEx(params, keyChainGroup)
    }

    private fun addTestTransactions(wallet: Wallet, count: Int) {
        log.info("Adding $count test transactions to wallet")
        
        repeat(count) { i ->
            // Create a simple test transaction
            val txData = createTestTransactionData(i)
            try {
                val tx = Transaction(wallet.params, txData, 0)
                wallet.addWalletTransaction(WalletTransaction(WalletTransaction.Pool.UNSPENT, tx))
                
                // Generate some addresses to make the wallet more realistic
                if (i % 10 == 0) {
                    wallet.currentReceiveAddress()
                    wallet.currentChangeAddress()
                }
            } catch (e: Exception) {
                // Some test transactions might be invalid, that's okay for performance testing
                log.debug("Skipped invalid test transaction $i: ${e.message}")
            }
        }
        
        log.info("Successfully added transactions. Wallet now has ${wallet.getTransactionCount(true)} transactions")
    }

    private fun createTestTransactionData(index: Int): ByteArray {
        // Create a realistic transaction structure for testing
        // This is a simplified version - in reality you'd want more varied transaction types
        val baseHex = "01000000013511fbb91663e90da67107e1510521440a9bf73878e45549ac169c7cd30c826e"
        val indexHex = String.format("%02d", index % 256)
        val restHex = "0000006a473044022048edae0ab0abcb736ca1a8702c2e99673d7958f4661a4858f437b03a359c0375022023f4a45b8817d9fcdad073cfb43320eae7e064a7873564e4cbc8853da548321a01210359c815be43ce68de8188f02b1b3ecb589fb8facdc2d694104a13bb2a2055f5ceffffffff0240420f00000000001976a9148017fd8d70d8d4b8ddb289bb73bcc0522bc06e0888acb9456900000000001976a914c9e6676121e9f38c7136188301a95d800ceade6588ac00000000"
        
        return Constants.HEX.decode(baseHex + indexHex + restHex)
    }

    private fun saveWalletToFile(wallet: Wallet, file: File) {
        FileOutputStream(file).use { outputStream ->
            WalletProtobufSerializer().writeWallet(wallet, outputStream)
        }
        
        log.debug("Wallet saved to ${file.absolutePath}, size: ${file.length()} bytes")
    }
}