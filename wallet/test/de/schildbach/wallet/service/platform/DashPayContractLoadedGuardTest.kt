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
import org.dashj.platform.dpp.contract.DataContract
import org.dashj.platform.sdk.client.ClientAppDefinition
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
}
