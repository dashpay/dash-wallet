/*
 * Copyright 2026 Dash Core Group.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.schildbach.wallet.ui.more

import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.more.connections.ConnectAvailability
import de.schildbach.wallet.ui.more.connections.PlatformDashConnectRepository
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.dash.wallet.common.WalletDataProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** The Tools entry to Connections, which is also how a wallet without a username reaches onboarding. */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolsViewModelConnectionsTest {
    private val platformRepo = mockk<PlatformRepo>(relaxed = true)
    private val identityRepository = mockk<IdentityRepository>(relaxed = true)
    private val originalSupportsPlatform = Constants.SUPPORTS_PLATFORM
    private val viewModels = mutableListOf<ToolsViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Platform support depends on the device ABI, which a plain JVM test does not report.
        Constants.SUPPORTS_PLATFORM = true
        ConnectAvailability.resetForTest()
    }

    @After
    fun teardown() {
        // The ViewModels collect the shared availability on Main; stop them before Main is reset.
        viewModels.forEach { it.viewModelScope.cancel() }
        ConnectAvailability.resetForTest()
        Constants.SUPPORTS_PLATFORM = originalSupportsPlatform
        Dispatchers.resetMain()
    }

    private fun viewModel(identityConfig: BlockchainIdentityConfig = mockk(relaxed = true)): ToolsViewModel {
        val walletData = mockk<WalletDataProvider>(relaxed = true)
        every { walletData.wallet!!.watchingKey.serializePubB58(any()) } returns "xpub"
        val repository = PlatformDashConnectRepository(mockk(relaxed = true), platformRepo, identityRepository, mockk(relaxed = true))
        return ToolsViewModel(
            walletData, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true) { every { observeState() } returns emptyFlow() },
            mockk(relaxed = true), identityConfig, mockk(relaxed = true), repository
        ).also { viewModels += it }
    }

    @Test
    fun firstVisitWithoutAUsername_showsConnectionsOnceTheNetworkRunsProtocol14() {
        assumeTrue(ConnectAvailability.isSupported)
        every { identityRepository.blockchainIdentity } returns null
        coEvery { platformRepo.readProtocolVersion() } returns ConnectAvailability.MIN_PROTOCOL_VERSION
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.connectionsAvailable)
        viewModel.refreshConnectionsAvailability()
        assertTrue(viewModel.uiState.value.connectionsAvailable)
    }

    @Test
    fun networkBelowProtocol14_keepsConnectionsHidden() {
        assumeTrue(ConnectAvailability.isSupported)
        every { identityRepository.blockchainIdentity } returns null
        coEvery { platformRepo.readProtocolVersion() } returns 13
        val viewModel = viewModel()
        viewModel.refreshConnectionsAvailability()
        assertFalse(viewModel.uiState.value.connectionsAvailable)
    }

    @Test
    fun usernameReadThatEndsLater_keepsConnectionsShown() {
        assumeTrue(ConnectAvailability.isSupported)
        coEvery { platformRepo.readProtocolVersion() } returns ConnectAvailability.MIN_PROTOCOL_VERSION
        // A later visit: the version is already known, so Connections shows while the username loads.
        runBlocking { ConnectAvailability.isAvailable(platformRepo) }
        val usernameRead = CompletableDeferred<String?>()
        val identityConfig = mockk<BlockchainIdentityConfig>(relaxed = true)
        coEvery { identityConfig.get(BlockchainIdentityConfig.IDENTITY_ID) } coAnswers { usernameRead.await() }
        val viewModel = viewModel(identityConfig)
        assertTrue(viewModel.uiState.value.connectionsAvailable)
        usernameRead.complete(null)
        assertTrue(viewModel.uiState.value.connectionsAvailable)
    }
}
