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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service.platform

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.dashj.platform.dpp.contract.DataContract
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.client.ClientAppDefinition
import org.dashj.platform.sdk.platform.Contracts
import org.dashj.platform.sdk.platform.Platform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard for the checkDatabaseIntegrity NPE (PlatformSyncService:1195):
 * rebuilding a ContactRequest goes `ContactRequest.builder -> Documents.create
 * -> Contracts.get`, and `Contracts.get` returns the app definition's CACHED
 * contract — null until the dashpay contract document has actually been
 * fetched. `hasApp("dashpay")` passes as soon as the app REGISTRATION exists,
 * so the integrity pass ran too early and NPEd out every cycle. The guard is
 * strictly stronger: the contract object itself must be present.
 */
class DashPayContractLoadedGuardTest {

    private fun platformWith(appMap: HashMap<String, ClientAppDefinition>): Platform =
        mockk<Platform> { every { apps } returns appMap }

    private fun appDefinition(dataContract: DataContract?): ClientAppDefinition =
        mockk<ClientAppDefinition> { every { contract } returns dataContract }

    @Test
    fun contractLoaded_passes() {
        val platform = platformWith(hashMapOf("dashpay" to appDefinition(mockk<DataContract>())))
        assertTrue(PlatformSynchronizationService.isDashPayContractLoaded(platform))
    }

    @Test
    fun appRegisteredButContractNotFetchedYet_fails() {
        // The NPE shape: hasApp("dashpay") is true (the registration exists),
        // but the contract document has not been fetched.
        val platform = platformWith(hashMapOf("dashpay" to appDefinition(null)))
        assertFalse(PlatformSynchronizationService.isDashPayContractLoaded(platform))
    }

    @Test
    fun noDashPayAppAtAll_fails() {
        assertFalse(PlatformSynchronizationService.isDashPayContractLoaded(platformWith(hashMapOf())))
        val otherAppsOnly = platformWith(hashMapOf("dpns" to appDefinition(mockk<DataContract>())))
        assertFalse(PlatformSynchronizationService.isDashPayContractLoaded(otherAppsOnly))
    }

    @Test
    fun aThrowingPlatform_isTreatedAsNotLoaded_neverPropagates() {
        val platform = mockk<Platform> { every { apps } throws IllegalStateException("not initialized") }
        assertFalse(PlatformSynchronizationService.isDashPayContractLoaded(platform))
    }

    // ---- ensureDashPayContractLoaded: the guard used to wait for a load nothing ever made ----

    private val dashpayId = Identifier.from(ByteArray(32) { 7 })

    /** A platform whose contracts.get behaves like the real one: on success it fills apps["dashpay"].contract. */
    private fun loadingPlatform(onGet: (ClientAppDefinition) -> DataContract?): Pair<Platform, Contracts> {
        val app = ClientAppDefinition(dashpayId)
        val appMap = hashMapOf("dashpay" to app)
        val contracts = mockk<Contracts> {
            every { get(dashpayId) } answers { onGet(app) }
        }
        val platform = mockk<Platform> {
            every { apps } returns appMap
            every { this@mockk.contracts } returns contracts
        }
        return platform to contracts
    }

    @Test
    fun ensureLoaded_fetchesTheContract_soTheGuardPasses() = runBlocking {
        val (platform, contracts) = loadingPlatform { app ->
            mockk<DataContract>().also { app.contract = it }
        }
        assertFalse(PlatformSynchronizationService.isDashPayContractLoaded(platform))

        assertTrue(PlatformSynchronizationService.ensureDashPayContractLoaded(platform))
        assertTrue(PlatformSynchronizationService.isDashPayContractLoaded(platform))
        verify(exactly = 1) { contracts.get(dashpayId) }

        // Already loaded: no second network fetch.
        assertTrue(PlatformSynchronizationService.ensureDashPayContractLoaded(platform))
        verify(exactly = 1) { contracts.get(dashpayId) }
    }

    @Test
    fun ensureLoaded_aFailedFetch_isNotLoaded_neverThrows() = runBlocking {
        // Contracts.get NPEs when the proved getDataContract comes back null.
        val (platform, _) = loadingPlatform { throw NullPointerException() }
        assertFalse(PlatformSynchronizationService.ensureDashPayContractLoaded(platform))
        assertFalse(PlatformSynchronizationService.isDashPayContractLoaded(platform))
    }

    @Test
    fun ensureLoaded_aHungFetch_isBounded() = runBlocking {
        val (platform, _) = loadingPlatform {
            Thread.sleep(2_000)
            null
        }
        val startedAt = System.currentTimeMillis()
        assertFalse(PlatformSynchronizationService.ensureDashPayContractLoaded(platform, timeoutMs = 100L))
        assertTrue(System.currentTimeMillis() - startedAt < 1_500L)
    }

    @Test
    fun ensureLoaded_withoutADashPayApp_isNotLoaded() = runBlocking {
        assertFalse(PlatformSynchronizationService.ensureDashPayContractLoaded(platformWith(hashMapOf())))
        Unit
    }
}
