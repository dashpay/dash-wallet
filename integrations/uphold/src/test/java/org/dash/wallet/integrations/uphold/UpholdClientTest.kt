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

package org.dash.wallet.integrations.uphold

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.dash.wallet.integrations.uphold.api.RemoteDataSource
import org.dash.wallet.integrations.uphold.api.UpholdService
import org.dash.wallet.integrations.uphold.data.UpholdCapability
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.MockitoAnnotations
import java.net.HttpURLConnection

class UpholdClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var api: UpholdService

    private val capabilitiesJson = """
        {
            "category": "permissions",
            "enabled": true,
            "key": "withdrawals",
            "name": "Withdrawals",
            "requirements": ["user-must-submit-identity"],
            "restrictions": []
        }
    """

    private val cardJson = """
        [{
          "address": {
            "dash": "XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U",
            "wire": "some id"
          },
          "available": "45.00",
          "balance": "45.00",
          "currency": "DASH",
          "id": "024e51fc-5513-4d82-882c-9b22024280cc",
          "label": "DASH card",
          "lastTransactionAt": "2018-08-01T09:53:44.617Z",
          "normalized": [{
            "available": "4500.00",
            "balance": "4500.00",
            "currency": "USD"
          }],
          "settings": {
            "position": 1,
            "protected": false,
            "starred": true
          }
        },
        {
          "address": {
            "bitcoin": "ms22VBPSahNTxHZNkYo2d4Rmw1Tgfx6ojr"
          },
          "available": "146.38",
          "balance": "146.38",
          "currency": "EUR",
          "id": "bc9b3911-4bc1-4c6d-ac05-0ae87dcfc9b3",
          "label": "EUR card",
          "lastTransactionAt": "2018-08-01T09:53:51.258Z",
          "normalized": [{
            "available": "170.96",
            "balance": "170.96",
            "currency": "USD"
          }],
          "settings": {
            "position": 2,
            "protected": false,
            "starred": true
          }
        }]
    """

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        mockWebServer = MockWebServer()
        mockWebServer.start(8080)
        api = RemoteDataSource().buildApi(UpholdService::class.java, "http://127.0.0.1:8080", OkHttpClient())
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun getCapabilities_returnsCapabilitiesObject() = runTest {
        val response = MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(capabilitiesJson)
        mockWebServer.enqueue(response)

        val expected = UpholdCapability(
            "withdrawals",
            "Withdrawals",
            "permissions",
            true,
            listOf("user-must-submit-identity"),
            listOf()
        )

        val actualResponse = api.getCapabilities("crypto_withdrawals")
        assertEquals(expected, actualResponse.body())
    }

    @Test
    fun getCards_returnsCardList() {
        val response = MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(cardJson)
        mockWebServer.enqueue(response)

        val actualResponse = api.cards.execute()
        val card = actualResponse.body()?.get(0)

        assertEquals(2, actualResponse.body()?.size)
        assertEquals("XjBya4EnibUyxubEA8D2Y8KSrBMW1oHq5U", card?.address?.cryptoAddress)
        assertEquals("some id", card?.address?.wireId)
    }
}
