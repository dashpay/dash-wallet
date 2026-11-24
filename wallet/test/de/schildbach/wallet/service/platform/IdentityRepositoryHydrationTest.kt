/*
 * Copyright 2026 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.service.platform

import android.app.Application
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.more.connections.AppConnectKeyStateTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.bitcoinj.core.Base58
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.WalletDataProvider
import org.dashj.platform.dashpay.BlockchainIdentity
import org.dashj.platform.dashpay.IdentityStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reloading a saved identity must restore its name slots and preorder salts exactly as they were saved. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE, shadows = [AppConnectKeyStateTest.ShadowSdkEnums::class])
class IdentityRepositoryHydrationTest {
    private val wallet = mockk<Wallet>(relaxed = true)
    private val provider = mockk<WalletDataProvider>(relaxed = true)
    private val platform = mockk<PlatformRepo>(relaxed = true)
    private val repository = spyk(
        IdentityRepositoryImpl(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            provider, platform, mockk(relaxed = true), mockk(relaxed = true)
        )
    )
    private val userId = Base58.encode(ByteArray(32) { 7 })
    private val salt = ByteArray(32) { 3 }
    private val secondarySalt = ByteArray(32) { 5 }

    init {
        every { provider.wallet } returns wallet
        every { repository.authenticationGroupExtension } returns mockk(relaxed = true)
        every { platform.platform.platform.params } returns TestNet3Params.get()
    }

    @Test
    fun registeredIdentityWithOneName_reloadsWithoutASecondaryName() {
        val identity = load(identityData(IdentityCreationState.IDENTITY_REGISTERED, "dashuser2"))

        assertEquals("dashuser2", identity.primaryUsername)
        assertNull(identity.secondaryUsername)
        assertEquals(listOf("dashuser2"), identity.getUsernames())
    }

    @Test
    fun identityWithTwoNames_reloadsEachNameInItsOwnSlot() {
        val identity = load(identityData(IdentityCreationState.IDENTITY_REGISTERED, "Dashuser", "dashuser2"))

        assertEquals("Dashuser", identity.primaryUsername)
        assertEquals("dashuser2", identity.secondaryUsername)
    }

    @Test
    fun interruptedPreorder_reloadsTheSavedSalt() {
        val identity = load(
            identityData(IdentityCreationState.PREORDER_REGISTERING, "dashuser2").apply { preorderSalt = salt }
        )

        assertArrayEquals(salt, identity.saltForUsername("dashuser2", false))
    }

    @Test
    fun interruptedSecondaryPreorder_reloadsBothSavedSalts() {
        val identity = load(
            identityData(IdentityCreationState.PREORDER_REGISTERING, "Dashuser", "dashuser2").apply {
                preorderSalt = salt
                preorderSaltSecondary = secondarySalt
            }
        )

        assertArrayEquals(salt, identity.saltForUsername("Dashuser", false))
        assertArrayEquals(secondarySalt, identity.saltForUsername("dashuser2", false))
    }

    private fun load(data: BlockchainIdentityData): BlockchainIdentity = repository.initBlockchainIdentity(data, wallet)

    private fun identityData(state: IdentityCreationState, username: String, usernameSecondary: String? = null) =
        BlockchainIdentityData(state, null, username, usernameSecondary, userId, false).apply {
            registrationStatus = IdentityStatus.REGISTERED
        }
}
