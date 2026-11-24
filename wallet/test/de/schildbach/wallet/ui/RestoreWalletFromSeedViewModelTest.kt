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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.WalletApplication.WalletInstallResult
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.WalletFactory
import de.schildbach.wallet.util.MnemonicCodeExt
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.DeterministicKeyChain
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class RestoreWalletFromSeedViewModelTest {
    /** The existing wallet's persisted scan-start date, in seconds. */
    private val existingDateSecs = 1_600_000_000L

    /** The date the user picks on the restore screen, in seconds. */
    private val selectedDateSecs = 1_700_000_000L

    /** A real, DataStore-backed config holding the existing wallet's date. */
    private val blockchainServiceConfig by lazy {
        BlockchainServiceConfig(ApplicationProvider.getApplicationContext(), mockk(relaxed = true)).also {
            runBlocking { it.setWalletCreationDate(existingDateSecs) }
        }
    }

    private fun viewModel(
        app: WalletApplication,
        factory: WalletFactory,
        configuration: Configuration = mockk(relaxed = true),
        securityFunctions: SecurityFunctions = mockk(relaxed = true)
    ) = RestoreWalletFromSeedViewModel(
        app, factory, configuration, securityFunctions, mockk(relaxed = true), blockchainServiceConfig
    ).apply { setWalletCreationDate(selectedDateSecs * 1000) }

    private val words = "abandon ".repeat(11).plus("about").split(" ")

    /**
     * What the user typed, which [mnemonicCode] takes for [words] or rejects, as each test says.
     * Lowercased, it is what a restore stored before a phrase had to match a wordlist exactly.
     */
    private val typedWords = listOf("Abandon", "ábandon") + words.drop(2)

    private val mnemonicCode = mockk<MnemonicCodeExt>()

    @Before
    fun setUp() {
        mockkStatic(SecurityGuard::class)
        every { SecurityGuard.getInstance() } returns mockk(relaxed = true)
        mockkObject(MnemonicCodeExt.Companion)
        every { MnemonicCodeExt.getInstance() } returns mnemonicCode
        every { mnemonicCode.checkExact(any(), any(), any()) } returns words
    }

    @After
    fun tearDown() {
        unmockkObject(MnemonicCodeExt.Companion)
        unmockkStatic(SecurityGuard::class)
    }

    @Test
    fun `a refused replacement reports REFUSED and runs none of the restore follow-ups`() = runBlocking {
        val app = mockk<WalletApplication>(relaxed = true)
        every { app.isWalletReplacementRefused } returns false
        every { app.installWallet(any(), false) } returns WalletInstallResult.REFUSED
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any()) } returns mockk<Wallet>()
        val configuration = mockk<Configuration>(relaxed = true)
        val viewModel = viewModel(app, factory, configuration)

        assertEquals(SeedRestoreResult.REFUSED, viewModel.restoreWalletFromSeed(words))
        verify(exactly = 0) { app.resetBlockchainState() }
        verify(exactly = 0) { configuration.disarmBackupSeedReminder() }
        verify(exactly = 0) { configuration.isRestoringBackup = any() }
        // The refusal raced past the up-front check: the selected date must
        // still not overwrite the existing wallet's scan start.
        assertEquals(existingDateSecs, blockchainServiceConfig.getWalletCreationDate())
    }

    @Test
    fun `a replacement refused up front builds no wallet and keeps the existing creation date`() = runBlocking {
        val app = mockk<WalletApplication>(relaxed = true)
        every { app.isWalletReplacementRefused } returns true
        val factory = mockk<WalletFactory>()

        assertEquals(SeedRestoreResult.REFUSED, viewModel(app, factory).restoreWalletFromSeed(words))
        coVerify(exactly = 0) { factory.restoreFromSeed(any(), any()) }
        verify(exactly = 0) { app.installWallet(any(), any()) }
        assertEquals(existingDateSecs, blockchainServiceConfig.getWalletCreationDate())
    }

    @Test
    fun `an accepted replacement persists the selected creation date`() = runBlocking {
        val app = mockk<WalletApplication>(relaxed = true)
        every { app.isWalletReplacementRefused } returns false
        every { app.installWallet(any(), false) } answers {
            // Not yet: the date follows the acceptance.
            assertEquals(existingDateSecs, runBlocking { blockchainServiceConfig.getWalletCreationDate() })
            WalletInstallResult.INSTALLED
        }
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any()) } returns mockk<Wallet>()

        assertEquals(SeedRestoreResult.RESTORED, viewModel(app, factory).restoreWalletFromSeed(words))
        assertEquals(selectedDateSecs, blockchainServiceConfig.getWalletCreationDate())
    }

    /**
     * MO-975 pre-review: a restore started before another onboarding screen
     * installed a wallet must not install over it — but forgot-PIN recovery,
     * which replaces the installed wallet on purpose, still does.
     */
    @Test
    fun `an onboarding restore over an installed wallet is dropped, a forgot-PIN recovery is not`() = runBlocking {
        // The app's atomic install refuses an onboarding restore over the
        // saved wallet and lets forgot-PIN recovery replace it.
        val app = appWithWalletOf(words)
        every { app.installWallet(any(), false) } returns WalletInstallResult.ALREADY_INSTALLED
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any()) } returns mockk<Wallet>()

        assertEquals(SeedRestoreResult.WALLET_ALREADY_INSTALLED, viewModel(app, factory).restoreWalletFromSeed(words))
        verify(exactly = 0) { app.installWallet(any(), true) }
        // A dropped restore leaves the installed wallet's wordlist alone.
        verify(exactly = 0) { mnemonicCode.checkExact(any(), any(), true) }
        assertEquals(existingDateSecs, blockchainServiceConfig.getWalletCreationDate())

        assertEquals(
            SeedRestoreResult.RESTORED,
            viewModel(app, factory).restoreWalletFromSeed(words, forgotPinRecovery = true)
        )
        verify(exactly = 1) { app.installWallet(any(), true) }
    }

    @Test
    fun `the wallet is built from the wordlist's spelling of the phrase, not from the words as typed`() = runBlocking {
        val app = mockk<WalletApplication>(relaxed = true)
        every { app.isWalletReplacementRefused } returns false
        every { app.installWallet(any(), false) } returns WalletInstallResult.INSTALLED
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any()) } returns mockk<Wallet>()

        assertEquals(SeedRestoreResult.RESTORED, viewModel(app, factory).restoreWalletFromSeed(typedWords))
        // Looked up read-only; the language is selected once the wallet is installed.
        verifyOrder {
            mnemonicCode.checkExact(any(), typedWords, false)
            app.installWallet(any(), false)
            mnemonicCode.checkExact(any(), words, true)
        }
        coVerify(exactly = 1) { factory.restoreFromSeed(any(), words) }
    }

    @Test
    fun `a phrase that is not exactly valid builds no wallet and says why`() = runBlocking {
        every { mnemonicCode.checkExact(any(), any(), any()) } throws MnemonicException.MnemonicWordException("ábandon")
        val app = mockk<WalletApplication>(relaxed = true)
        val factory = mockk<WalletFactory>()
        val viewModel = viewModel(app, factory)

        val thrown = runCatching { viewModel.restoreWalletFromSeed(typedWords) }.exceptionOrNull()
        assertEquals("ábandon", (thrown as MnemonicException.MnemonicWordException).badWord)
        assertNull(viewModel.recoverPin(typedWords))
        coVerify(exactly = 0) { factory.restoreFromSeed(any(), any()) }
        verify(exactly = 0) { app.installWallet(any(), any()) }
    }

    @Test
    fun `forgot-PIN matches the wordlist's spelling against the wallet's phrase`() = runBlocking {
        val securityFunctions = mockk<SecurityFunctions>()
        coEvery { securityFunctions.decryptSeed(any()) } returns DeterministicSeed(words, null, "", 0L)

        val recovered = viewModel(mockk(relaxed = true), mockk(), securityFunctions = securityFunctions)
            .recoverPin(typedWords)

        assertEquals(false, recovered?.requiresReset)
        coVerify(exactly = 1) { securityFunctions.decryptSeed(any()) }
    }

    /** A wallet restored before the restore screen stored the wordlist's spelling. */
    @Test
    fun `forgot-PIN still matches a wallet that holds the words as they were typed`() = runBlocking {
        val storedWords = typedWords.map { it.lowercase() }
        val securityFunctions = mockk<SecurityFunctions>()
        coEvery { securityFunctions.decryptSeed(any()) } returns DeterministicSeed(storedWords, null, "", 0L)
        val viewModel = viewModel(mockk(relaxed = true), mockk(), securityFunctions = securityFunctions)

        assertEquals(false, viewModel.recoverPin(typedWords)?.requiresReset)
        // The paper phrase is not what that wallet holds.
        assertNull(viewModel.recoverPin(words))
    }

    /** An installed wallet whose keys come from [storedWords], on a real key chain at [accountPath]. */
    private fun appWithWalletOf(
        storedWords: List<String>,
        accountPath: List<ChildNumber> = DeterministicKeyChain.BIP44_ACCOUNT_ZERO_PATH
    ): WalletApplication {
        val keyChain = DeterministicKeyChain.builder()
            .seed(DeterministicSeed(storedWords, null, "", 0L))
            .accountPath(ImmutableList.copyOf(accountPath))
            .build()
        val app = mockk<WalletApplication>(relaxed = true)
        every { app.isWalletReplacementRefused } returns false
        every { app.installWallet(any(), true) } returns WalletInstallResult.INSTALLED
        every { app.wallet!!.activeKeyChain } returns keyChain
        return app
    }

    /** The account path differs by network: the key check follows the installed chain's. */
    @Test
    fun `the forgot-PIN key check works at the testnet account path`() = runBlocking {
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any()) } returns mockk<Wallet>()
        val testnetPath = DeterministicKeyChain.BIP44_ACCOUNT_ZERO_PATH_TESTNET

        val matching = viewModel(appWithWalletOf(words, testnetPath), factory)
        assertEquals(SeedRestoreResult.RESTORED, matching.restoreWalletFromSeed(words, forgotPinRecovery = true))

        val otherWallet = "zoo ".repeat(11).plus("wrong").split(" ")
        val other = viewModel(appWithWalletOf(otherWallet, testnetPath), factory)
        assertEquals(SeedRestoreResult.INVALID_SEED, other.restoreWalletFromSeed(words, forgotPinRecovery = true))
    }

    /**
     * Forgot-PIN recovery that cannot recover the PIN verifies the phrase against the wallet's
     * keys and then replaces the wallet with itself: from the spelling it verified, or the
     * replacement is a different wallet saved over the user's.
     */
    @Test
    fun `a forgot-PIN replacement rebuilds the wallet from the spelling the installed wallet holds`() = runBlocking {
        val storedWords = typedWords.map { it.lowercase() }
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any()) } returns mockk<Wallet>()

        val legacy = viewModel(appWithWalletOf(storedWords), factory)
        assertEquals(SeedRestoreResult.RESTORED, legacy.restoreWalletFromSeed(typedWords, forgotPinRecovery = true))
        coVerify(exactly = 1) { factory.restoreFromSeed(any(), storedWords) }
        coVerify(exactly = 0) { factory.restoreFromSeed(any(), words) }

        val current = viewModel(appWithWalletOf(words), factory)
        assertEquals(SeedRestoreResult.RESTORED, current.restoreWalletFromSeed(typedWords, forgotPinRecovery = true))
        coVerify(exactly = 1) { factory.restoreFromSeed(any(), words) }
    }

    /** The installed wallet is no longer the one recovery verified: nothing may be installed over it. */
    @Test
    fun `a forgot-PIN replacement is refused when the phrase matches the installed wallet in no spelling`() = runBlocking {
        val otherWallet = "zoo ".repeat(11).plus("wrong").split(" ")
        val app = appWithWalletOf(otherWallet)
        val factory = mockk<WalletFactory>()

        val result = viewModel(app, factory).restoreWalletFromSeed(typedWords, forgotPinRecovery = true)

        assertEquals(SeedRestoreResult.INVALID_SEED, result)
        coVerify(exactly = 0) { factory.restoreFromSeed(any(), any()) }
        verify(exactly = 0) { app.installWallet(any(), any()) }
        // A phrase the wallet did not authenticate must not switch the app's wordlist.
        verify(exactly = 0) { mnemonicCode.checkExact(any(), any(), true) }
    }

    /** An earlier language search could accept, and so a wallet can hold, a phrase no wordlist validates. */
    @Test
    fun `forgot-PIN still recovers a wallet whose phrase no wordlist validates`() = runBlocking {
        every { mnemonicCode.checkExact(any(), any(), any()) } throws MnemonicException.MnemonicChecksumException()
        val storedWords = typedWords.map { it.lowercase() }
        val securityFunctions = mockk<SecurityFunctions>()
        coEvery { securityFunctions.decryptSeed(any()) } returns DeterministicSeed(storedWords, null, "", 0L)
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any()) } returns mockk<Wallet>()
        val viewModel = viewModel(appWithWalletOf(storedWords), factory, securityFunctions = securityFunctions)

        assertEquals(false, viewModel.recoverPin(typedWords)?.requiresReset)
        assertNull(viewModel.recoverPin(words))
        assertEquals(SeedRestoreResult.RESTORED, viewModel.restoreWalletFromSeed(typedWords, forgotPinRecovery = true))
        coVerify(exactly = 1) { factory.restoreFromSeed(any(), storedWords) }
        // A new restore stays strict.
        assertTrue(runCatching { viewModel.restoreWalletFromSeed(typedWords) }.exceptionOrNull() is MnemonicException)
        coVerify(exactly = 1) { factory.restoreFromSeed(any(), any()) }
    }
}
