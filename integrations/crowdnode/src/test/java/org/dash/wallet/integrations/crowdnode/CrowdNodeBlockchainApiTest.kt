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

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.runBlocking
import org.bitcoinj.params.MainNetParams
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeBlockchainApi
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeWebApi
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeBalance
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeIsAddressInUse
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.junit.Test
import org.mockito.kotlin.*
import retrofit2.Response
import kotlin.time.ExperimentalTime

@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
class CrowdNodeBlockchainApiTest {
    private val localConfig = mock<CrowdNodeConfig> {
        onBlocking { getPreference(CrowdNodeConfig.ACCOUNT_ADDRESS) } doReturn "XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U"
        onBlocking { getPreference(CrowdNodeConfig.ERROR) } doReturn ""
    }

    private val walletData = mock<WalletDataProvider> {
        on { wrapAllTransactions(any()) } doReturn listOf()
        on { networkParameters } doReturn MainNetParams.get()
        doNothing().whenever(mock).attachOnWalletWipedListener(any())
    }

    private val webApi = mock<CrowdNodeWebApi> {
        onBlocking { isAddressInUse(any()) } doReturn Response.success(CrowdNodeIsAddressInUse((false)))
        onBlocking { getBalance(any()) } doReturn Response.success(CrowdNodeBalance("XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U", 0.7, 0.7, 0.1))
    }

    @Test
    fun stopTrackingLinked_resetsInProgressStatus() {
        localConfig.stub {
            onBlocking { getPreference(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) } doReturn OnlineAccountStatus.Linking.ordinal
        }
        val api = CrowdNodeBlockchainApi(webApi, mock(), walletData, mock(), mock(), localConfig, mock())
        api.stopTrackingLinked()

        assertEquals(OnlineAccountStatus.None, api.onlineAccountStatus.value)
        assertEquals(SignUpStatus.NotStarted, api.signUpStatus.value)
    }

    @Test
    fun stopTrackingLinked_doesNotDemoteConfirmedStatus() {
        localConfig.stub {
            onBlocking { getPreference(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) } doReturn OnlineAccountStatus.Confirming.ordinal
        }
        val api = CrowdNodeBlockchainApi(mock(), mock(), walletData, mock(), mock(), localConfig, mock())
        api.stopTrackingLinked()

        assertEquals(OnlineAccountStatus.Confirming, api.onlineAccountStatus.value)
        assertEquals(SignUpStatus.LinkedOnline, api.signUpStatus.value)
    }

    @Test
    fun refreshBalance_notSignedIn_doesNotCallWebApi() {
        runBlocking {
            localConfig.stub {
                onBlocking { getPreference(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) } doReturn OnlineAccountStatus.None.ordinal
            }
            val api = CrowdNodeBlockchainApi(webApi, mock(), walletData, mock(), mock(), localConfig, mock())
            api.refreshBalance()

            assertEquals(SignUpStatus.NotStarted, api.signUpStatus.value)
            verifyNoInteractions(webApi)
        }
    }
}