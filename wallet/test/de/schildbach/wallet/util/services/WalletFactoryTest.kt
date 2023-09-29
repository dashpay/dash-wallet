package de.schildbach.wallet.util.services

import android.content.ContentResolver
import android.net.Uri
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.DashWalletFactory
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.utils.BriefLogFormatter
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException

class WalletFactoryTest {
    private val contentResolver = mockk<ContentResolver>()
    private val application = mockk<WalletApplication>()

    @Before
    fun setup() {
        every { application.contentResolver } returns contentResolver
    }

    @Test
    fun createTest() {
        val walletFactory = DashWalletFactory(application)
        val context = Context(MainNetParams.get())
        walletFactory.create(context.params)
    }

    @Test
    fun restoreFromSeedTest() {
        val walletFactory = DashWalletFactory(application)
        var context = Context(MainNetParams.get())
        context = Context.get()

        try {
            walletFactory.restoreFromSeed(
                context.params,
                "innocent two another top giraffe trigger urban top oyster stove gym danger".split(' ')
            )
        } catch (e: Exception) {
            println(e.message)
            e.printStackTrace()
            fail(e.message)
        }
    }

    @Test
    fun restoreFromEncryptedFileTest() {
        val walletFactory = DashWalletFactory(application)
        val context = Context(MainNetParams.get())

        val withPinUri = mockk<Uri>()
        val withoutPinUri = mockk<Uri>()

        every {
            contentResolver.openInputStream(withoutPinUri)
        } answers {
            WalletFactoryTest::class.java.getResourceAsStream("dash-wallet-backup-5.17.5-nopin")
        }

        every {
            contentResolver.openInputStream(withPinUri)
        } answers {
            val stream = WalletFactoryTest::class.java.getResourceAsStream("dash-wallet-backup-5.17.5-pin")
            stream
        }

        val (walletWithPin, newSeed) = walletFactory.restoreFromFile(context.params, withPinUri, "1111")
        assertTrue(walletWithPin.isEncrypted)
        assertTrue(walletWithPin.checkPassword("1111"))
        assertFalse(newSeed)
        val decryptedSeed = walletWithPin.keyChainSeed.decrypt(
            walletWithPin.keyCrypter,
            "",
            walletWithPin.keyCrypter?.deriveKey("1111")
        )
        assertEquals(
            "innocent two another top giraffe trigger urban top oyster stove gym danger".split(' '),
            decryptedSeed.mnemonicCode
        )

        val (walletWithoutPin, newSeedWithoutPin) = walletFactory.restoreFromFile(context.params, withoutPinUri, "1111")
        assertFalse(walletWithoutPin.isEncrypted)
        assertFalse(newSeedWithoutPin)
        assertEquals(
            "innocent two another top giraffe trigger urban top oyster stove gym danger".split(' '),
            walletWithoutPin.keyChainSeed.mnemonicCode
        )
    }

    @Test
    fun restoreFromKeyFileTest() {
        val walletFactory = DashWalletFactory(application)
        val context = Context(TestNet3Params.get())
        val keysUri = mockk<Uri>()

        every {
            contentResolver.openInputStream(keysUri)
        } answers {
            WalletFactoryTest::class.java.getResourceAsStream("backup-base58-testnet")
        }

        val (walletFromKeys, newSeed) = walletFactory.restoreFromFile(context.params, keysUri, "")
        assertTrue(!walletFromKeys.isEncrypted)
        assertTrue(newSeed)
    }

    @Test(expected = IOException::class)
    fun restoreFromEncryptedFileTest_wrongNetwork() {
        val walletFactory = DashWalletFactory(application)
        val contextTestnet = Context(TestNet3Params.get())

        val withoutPinUri = mockk<Uri>()

        every {
            contentResolver.openInputStream(withoutPinUri)
        } answers {
            WalletFactoryTest::class.java.getResourceAsStream("dash-wallet-backup-5.17.5-nopin")
        }

        walletFactory.restoreFromFile(contextTestnet.params, withoutPinUri, "1111")
    }

    @Test(expected = IOException::class)
    fun restoreFromKeyFileTest_wrongNetwork() {
        val walletFactory = DashWalletFactory(application)
        val context = Context(MainNetParams.get())
        val keysUri = mockk<Uri>()

        every {
            contentResolver.openInputStream(keysUri)
        } answers {
            WalletFactoryTest::class.java.getResourceAsStream("backup-base58-testnet")
        }

        walletFactory.restoreFromFile(context.params, keysUri, "")
    }

    @Test(expected = IOException::class)
    fun restoreFromFileTest_wrongCoin() {
        val walletFactory = DashWalletFactory(application)
        val contextTestnet = Context(TestNet3Params.get())

        val withoutPinUri = mockk<Uri>()

        every {
            contentResolver.openInputStream(withoutPinUri)
        } answers {
            WalletFactoryTest::class.java.getResourceAsStream("bitcoin-backup-protobuf-testnet")
        }

        walletFactory.restoreFromFile(contextTestnet.params, withoutPinUri, "")
    }

    @Test(expected = IOException::class)
    fun restoreFromKeyFileTest_wrongCoin() {
        val walletFactory = DashWalletFactory(application)
        val context = Context(MainNetParams.get())
        val keysUri = mockk<Uri>()

        every {
            contentResolver.openInputStream(keysUri)
        } answers {
            WalletFactoryTest::class.java.getResourceAsStream("bitcoin-backup-base58-testnet")
        }

        walletFactory.restoreFromFile(context.params, keysUri, "")
    }
}
