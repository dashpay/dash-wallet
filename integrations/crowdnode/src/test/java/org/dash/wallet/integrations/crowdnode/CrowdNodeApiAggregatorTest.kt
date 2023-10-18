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
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.*
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.Resource
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApiAggregator
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeBlockchainApi
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeWebApi
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.transactions.CrowdNodeWelcomeToApiResponse
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.time.ExperimentalTime

@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
class CrowdNodeApiAggregatorTest {
    private val networkParams = TestNet3Params.get()
    private val welcomeData = "020000000263779831af3973f7f8f1c390c363c3eae19bcc60c0296852ecea832e16022769010000006a473044022042dcb3849c7018cc99879bcea881284c3a5848ae5caf4c7d1390a9cbde812e780220557da9f91b088c5a59db6ed82ea34e5f5a4f5d1bf10fa5ccff920c4a461ecb4a012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff90bd741046ab7e68d532ac0466920729f3070b1184a4703ac620601ff594d0ff000000006a47304402202b512d7a20279a1aed12dd05619a2951412ff3d0ded96327a43ddd02dba0c1ad0220337f62aafb3721575e2ccd3c6bac97591186e6cca92f54896e08c1cd529e7be6012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02244e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888ac5c1dc4680a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acfbdc0a00" // ktlint-disable max-line-length
    private val welcomeResponseTx = Transaction(networkParams, Utils.HEX.decode(welcomeData))
    private val confirmationTxData = "01000000016fdb75611fd8892d8d19707f0d0958da5b930c635750f2c8c5bf5a48458a8ffd000000006b483045022100ff77055377b33afb8fc2f622fda59816394a567c50e98569fe8ab68d797948b802204b05504b3f837e80f4d0d8c3c6d98107e770572a8eaae66ddd14a660fe9674f101210275ab1f1c864e594c5e075ac45fbd01a45285701e429b842f8d0a1c872cf3a7baffffffff0149d00000000000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888ac00000000" // ktlint-disable max-line-length
    private val confirmationTx = Transaction(networkParams, Utils.HEX.decode(confirmationTxData))

    private val localConfig = mock<CrowdNodeConfig> {
        onBlocking { get(CrowdNodeConfig.BACKGROUND_ERROR) } doReturn ""
    }

    private val globalConfig = mock<Configuration> {
        on { crowdNodeAccountAddress } doReturn "yihMSMoesHX1JhbntTiV5Nptf5NLrmFMCu"
        on { crowdNodePrimaryAddress } doReturn "yd9CUc7wvATUS3GfdmcAhRZhG7719jhNf9"
    }

    private val walletData = mock<WalletDataProvider> {
        on { wrapAllTransactions(any()) } doReturn listOf()
        on { networkParameters } doReturn networkParams
        doNothing().whenever(mock).attachOnWalletWipedListener(any())
    }

    private val webApi = mock<CrowdNodeWebApi> {
        onBlocking { isApiAddressInUse(any()) } doReturn Pair(false, null)
        onBlocking { resolveBalance(any()) } doReturn Resource.success(Coin.COIN)
        onBlocking { getWithdrawalLimits(any()) } doReturn mapOf()
    }

    private val blockchainApi = mock<CrowdNodeBlockchainApi> {
        on { getFullSignUpTxSet() } doReturn null
        on { getApiAddressConfirmationTx() } doReturn null
    }

    @Before
    fun setup() {
        val welcomeConnected = "0200000002f7f6beb8d49ec4639394a663cd3ae08d9382ecfbb38e9cb85deaf835b74ad1be000000006a47304402202b467d0ae5f40633500096b01dbc5952efca40d50143739dc92f2b9fc8cf479c02206d3a04f11538ce4ff664b168abad0b02d135b3ec571b390616ac76f888e0ecaf012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffffd979b8815c9a17e956011b7b9767dcb1237501832392a1f5d2ed2e0d785754c2010000006a473044022079f2e9dd53d838978fe82a6034894c062381ac32bce190e0d862efff7e4a5ef002200eee7a5bd39f084cd8dc73d50d1fc1388e998750db3fadbd74ff2537bef0cd8e012102bf7c36100b0d394e79a1704b8bf9e030a62e139a293f5da891671c56d555f732feffffff02224e0000000000001976a914f57766c540e7e165092e739e115383bd04d2c21888acf91ec3680a0000001976a9140d5bcbeeb459af40f97fcb4a98e9d1ed13e904c888acfadc0a00" // ktlint-disable max-line-length
        val welcomeConnectedTx = Transaction(networkParams, Utils.HEX.decode(welcomeConnected))
        welcomeResponseTx.inputs[0].connect(welcomeConnectedTx.outputs[0])

        val confirmationConnected = "01000000017a4c4461b44d14a3866bc89482bdd35c800961879d65fc4e0542a377232f124b000000006a47304402206beee5884010d44501dee9094d9922af0c22b63f3afc1f3aa5c43bfcf59f38070220676e5bbeb712cbbbadbde6959cdee3367d1578bd06204178630cb1ba729073a20121025e25aa5f744bdd42f386a11efc584fa25afdaa299477038d2b86ac655287305effffffff0231d40000000000001976a914dcd48b7080eafd7b4db3bac7ea8480ccc3b1093388ac7d54a750000000001976a9149701a96ac4f8bb1a627be9928ccbc717ccce485788ac00000000" // ktlint-disable max-line-length
        val confirmationConnectedTx = Transaction(networkParams, Utils.HEX.decode(confirmationConnected))
        confirmationTx.inputs[0].connect(confirmationConnectedTx.outputs[0])
    }

    @Test
    fun stopTrackingLinked_resetsInProgressStatus() {
        runBlocking {
            localConfig.stub {
                onBlocking { get(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) } doReturn OnlineAccountStatus.Linking.ordinal
            }
            val api = CrowdNodeApiAggregator(webApi, blockchainApi, walletData, mock(), mock(), localConfig, globalConfig, mock(), mock()) // ktlint-disable max-line-length
            api.restoreStatus()
            api.stopTrackingLinked()

            assertEquals(OnlineAccountStatus.None, api.onlineAccountStatus.value)
            assertEquals(SignUpStatus.NotStarted, api.signUpStatus.value)
        }
    }

    @Test
    fun stopTrackingLinked_doesNotDemoteConfirmedStatus() {
        runBlocking {
            localConfig.stub {
                onBlocking {
                    get(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS)
                } doReturn OnlineAccountStatus.Confirming.ordinal
            }
            val api = CrowdNodeApiAggregator(
                mock(), blockchainApi, walletData, mock(), mock(),
                localConfig, globalConfig, mock(), mock()
            )
            api.restoreStatus()
            api.stopTrackingLinked()

            assertEquals(OnlineAccountStatus.Confirming, api.onlineAccountStatus.value)
            assertEquals(SignUpStatus.LinkedOnline, api.signUpStatus.value)
        }
    }

    @Test
    fun stopTrackingLinked_doesNotDemoteApiSignUpStatus() {
        runBlocking {
            localConfig.stub {
                onBlocking { get(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) } doReturn OnlineAccountStatus.None.ordinal
            }
            globalConfig.stub {
                on { crowdNodePrimaryAddress } doReturn ""
            }
            val mockFullSet = mock<FullCrowdNodeSignUpTxSet> {
                on { welcomeToApiResponse } doReturn CrowdNodeWelcomeToApiResponse(networkParams).apply {
                    matches(welcomeResponseTx)
                }
            }
            blockchainApi.stub {
                on { getFullSignUpTxSet() } doReturn mockFullSet
            }
            val api = CrowdNodeApiAggregator(webApi, blockchainApi, walletData, mock(), mock(), localConfig, globalConfig, mock(), mock()) // ktlint-disable max-line-length
            api.restoreStatus()
            assertEquals(SignUpStatus.Finished, api.signUpStatus.value)
            assertEquals(OnlineAccountStatus.None, api.onlineAccountStatus.value)

            api.stopTrackingLinked()

            assertEquals(SignUpStatus.Finished, api.signUpStatus.value)
            assertEquals(OnlineAccountStatus.None, api.onlineAccountStatus.value)
        }
    }

    @Test
    fun refreshBalance_notSignedIn_doesNotCallWebApi() {
        runBlocking {
            localConfig.stub {
                onBlocking { get(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) } doReturn OnlineAccountStatus.None.ordinal
            }
            val api = CrowdNodeApiAggregator(
                webApi, blockchainApi, walletData, mock(), mock(),
                localConfig, globalConfig, mock(), mock()
            )
            api.restoreStatus()
            api.refreshBalance()

            assertEquals(SignUpStatus.NotStarted, api.signUpStatus.value)
            verifyNoInteractions(webApi)
        }
    }

    @Test
    fun onlineStatusDone_restoredCorrectly() {
        runBlocking {
            localConfig.stub {
                onBlocking { get(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) } doReturn 6
            }
            val api = CrowdNodeApiAggregator(
                webApi, blockchainApi, walletData, mock(), mock(),
                localConfig, globalConfig, mock(), mock()
            )
            api.restoreStatus()
            assertEquals(SignUpStatus.LinkedOnline, api.signUpStatus.value)
            assertEquals(OnlineAccountStatus.Done, api.onlineAccountStatus.value)
        }
    }

    @Test
    fun onlineStatusCreating_restoredCorrectly() {
        runBlocking {
            localConfig.stub {
                onBlocking { get(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) } doReturn 4
            }
            globalConfig.stub {
                on { crowdNodePrimaryAddress } doReturn ""
            }
            val mockFullSet = mock<FullCrowdNodeSignUpTxSet> {
                on { welcomeToApiResponse } doReturn CrowdNodeWelcomeToApiResponse(networkParams).apply {
                    matches(welcomeResponseTx)
                }
            }
            blockchainApi.stub {
                on { getFullSignUpTxSet() } doReturn mockFullSet
            }
            val api = CrowdNodeApiAggregator(
                webApi, blockchainApi, walletData, mock(), mock(),
                localConfig, globalConfig, mock(), mock()
            )
            api.restoreStatus()
            assertEquals(SignUpStatus.Finished, api.signUpStatus.value)
            assertEquals(OnlineAccountStatus.Creating, api.onlineAccountStatus.value)
        }
    }

    @Test
    fun `linked online account restored correctly from confirmation transaction`(): Unit = runBlocking {
        localConfig.stub {
            onBlocking { get(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) } doReturn 0
        }
        globalConfig.stub {
            on { crowdNodeAccountAddress } doReturn ""
            on { crowdNodePrimaryAddress } doReturn ""
        }
        blockchainApi.stub {
            on { getApiAddressConfirmationTx() } doReturn confirmationTx
        }
        val bagMock = mock<TransactionBag> {
            on { isPubKeyHashMine(any(), any()) } doReturn true
        }
        walletData.stub {
            on { transactionBag } doReturn bagMock
        }
        webApi.stub {
            onBlocking {
                isApiAddressInUse(any())
            } doReturn Pair(true, Address.fromBase58(networkParams, "yd9CUc7wvATUS3GfdmcAhRZhG7719jhNf9"))
        }

        val api = CrowdNodeApiAggregator(
            webApi, blockchainApi, walletData, mock(), mock(),
            localConfig, globalConfig, mock(), mock()
        )
        api.restoreStatus()

        assertTrue(
            "Linked Online Account isn't restored correctly",
            api.onlineAccountStatus.value.ordinal >= OnlineAccountStatus.Linking.ordinal
        )
    }
}
