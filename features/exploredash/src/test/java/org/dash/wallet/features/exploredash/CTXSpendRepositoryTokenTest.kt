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

package org.dash.wallet.features.exploredash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.util.security.EncryptionProvider
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.RefreshTokenRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.RefreshTokenResponse
import org.dash.wallet.features.exploredash.network.authenticator.TokenAuthenticator
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendApi
import org.dash.wallet.features.exploredash.network.service.ctxspend.CTXSpendTokenApi
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.utils.CTXSpendConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.security.GeneralSecurityException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CTXSpendRepositoryTokenTest {
    private lateinit var config: CTXSpendConfig

    @Before
    fun setup() = runTest {
        val walletDataProvider = mock<WalletDataProvider> {
            on { attachOnWalletWipedListener(any()) } doAnswer {}
        }

        config = CTXSpendConfig(
            ApplicationProvider.getApplicationContext<Context>(),
            walletDataProvider,
            PlainTextEncryptionProvider
        )
        config.clearAll()
    }

    @After
    fun tearDown() = runTest {
        config.clearAll()
    }

    @Test
    fun refreshTokenStoresRotatedTokenAfterCallerCancels() = runTest {
        config.saveTokenState("old-access", "old-refresh", now = 1L)
        val tokenApi = BlockingTokenApi(RefreshTokenResponse("new-refresh", "new-access"))
        val repository = createRepository(tokenApi)

        val refreshJob = launch {
            repository.refreshToken()
        }

        tokenApi.started.await()
        refreshJob.cancel()
        tokenApi.complete()
        refreshJob.join()

        assertEquals("new-access", config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN))
        assertEquals("new-refresh", config.getSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN))
        assertTrue((config.get(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN_TIME) ?: 0L) > 1L)
        assertTrue((config.get(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN_TIME) ?: 0L) > 1L)
    }

    @Test
    fun refreshTokenPreservesTokensOnTransientFailure() = runTest {
        config.saveTokenState("old-access", "old-refresh", now = 1L)
        val repository = createRepository(FailingTokenApi(IOException("offline")))

        assertFalse(repository.refreshToken())

        assertEquals("old-access", config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN))
        assertEquals("old-refresh", config.getSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN))
        assertEquals(1L, config.get(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN_TIME))
        assertEquals(1L, config.get(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN_TIME))
    }

    @Test
    fun refreshTokenClearsTokensWhenRefreshTokenIsRejected() = runTest {
        config.saveTokenState("old-access", "old-refresh", now = 1L)
        val repository = createRepository(FailingTokenApi(http401()))

        assertFalse(repository.refreshToken())

        assertEquals("", config.getSecuredData(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN))
        assertEquals("", config.getSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN))
        assertEquals(0L, config.get(CTXSpendConfig.PREFS_KEY_ACCESS_TOKEN_TIME))
        assertEquals(0L, config.get(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN_TIME))
    }

    @Test
    fun refreshAccessTokenReturnsRotatedTokenWithoutNetworkWhenAnotherCallerRefreshed() = runTest {
        // Another caller already rotated the token; the failed request still carries the old one.
        config.saveTokenState("fresh-access", "fresh-refresh", now = 1L)
        // Throws if the network is hit - the rotated token should be returned without refreshing.
        val authenticator = TokenAuthenticator(
            FailingTokenApi(IllegalStateException("refresh endpoint must not be called")),
            config
        )

        val result = authenticator.refreshAccessToken(staleAccessToken = "stale-access")

        assertEquals("fresh-access", result)
        assertEquals("fresh-refresh", config.getSecuredData(CTXSpendConfig.PREFS_KEY_REFRESH_TOKEN))
    }

    private fun createRepository(tokenApi: CTXSpendTokenApi): CTXSpendRepository {
        return CTXSpendRepository(
            mock<CTXSpendApi>(),
            config,
            TokenAuthenticator(tokenApi, config)
        )
    }

    private class BlockingTokenApi(
        private val response: RefreshTokenResponse
    ) : CTXSpendTokenApi {
        val started = CompletableDeferred<Unit>()
        private val canComplete = CompletableDeferred<Unit>()

        override suspend fun refreshToken(refreshTokenRequest: RefreshTokenRequest): RefreshTokenResponse {
            started.complete(Unit)
            canComplete.await()
            return response
        }

        fun complete() {
            canComplete.complete(Unit)
        }
    }

    private class FailingTokenApi(
        private val error: Exception
    ) : CTXSpendTokenApi {
        override suspend fun refreshToken(refreshTokenRequest: RefreshTokenRequest): RefreshTokenResponse {
            throw error
        }
    }

    private object PlainTextEncryptionProvider : EncryptionProvider {
        @Throws(GeneralSecurityException::class, IOException::class)
        override fun encrypt(keyAlias: String, textToEncrypt: String): ByteArray =
            textToEncrypt.toByteArray()

        @Throws(GeneralSecurityException::class, IOException::class)
        override fun decrypt(keyAlias: String, encryptedData: ByteArray): String =
            encryptedData.toString(Charsets.UTF_8)

        override fun deleteKey(keyAlias: String) = Unit
    }

    private fun http401(): HttpException {
        val body = "{}".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<RefreshTokenResponse>(401, body))
    }
}
