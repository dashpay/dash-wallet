/*
 * Copyright (c) 2026. Dash Core Group.
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
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.util.security.EncryptionProvider
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.LoginRequest
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.LoginResponse
import org.dash.wallet.features.exploredash.network.authenticator.PiggyCardsAuthenticator
import org.dash.wallet.features.exploredash.network.service.piggycards.PiggyCardsTokenApi
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.utils.PiggyCardsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Verifies the PiggyCards auth hardening that mirrors PR #1492's CTX fixes:
 *  - re-logins are serialized by a process-wide (companion) lock across instances, and
 *  - a transient failure keeps the cached token while a genuine 401 clears it.
 */
@RunWith(RobolectricTestRunner::class)
class PiggyCardsAuthenticatorTest {

    private val identityEncryption = object : EncryptionProvider {
        override fun encrypt(keyAlias: String, textToEncrypt: String): ByteArray = textToEncrypt.toByteArray()
        override fun decrypt(keyAlias: String, encryptedData: ByteArray): String = String(encryptedData)
        override fun deleteKey(keyAlias: String) {}
    }

    private fun realConfig(): PiggyCardsConfig {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PiggyCardsConfig(context, mock<WalletDataProvider>(), identityEncryption)
    }

    private fun unauthorizedResponse(): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

    @Test
    fun reLogin_acrossInstances_isSerialized_byProcessWideLock() {
        val config = realConfig()
        runBlocking {
            config.setSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID, "user-1")
            config.setSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD, "pw-1")
        }

        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val tokenApi = object : PiggyCardsTokenApi {
            override suspend fun login(loginRequest: LoginRequest): LoginResponse {
                val now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { max(it, now) }
                Thread.sleep(200)
                concurrent.decrementAndGet()
                return LoginResponse(accessToken = "fresh-token", tokenType = "Bearer", expiresIn = 3600)
            }
        }
        val authenticatorA = PiggyCardsAuthenticator(tokenApi, config)
        val authenticatorB = PiggyCardsAuthenticator(tokenApi, config)
        val response = unauthorizedResponse()

        val threadA = thread { authenticatorA.authenticate(null, response) }
        val threadB = thread { authenticatorB.authenticate(null, response) }
        threadA.join()
        threadB.join()

        // The shared companion lock prevents overlapping re-logins across separate instances.
        assertEquals(1, maxConcurrent.get())
    }

    @Test
    fun reLogin_onTransientFailure_preservesToken() = runBlocking {
        val config = realConfig()
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID, "user-1")
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD, "pw-1")
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN, "existing-token")

        val tokenApi = object : PiggyCardsTokenApi {
            override suspend fun login(loginRequest: LoginRequest): LoginResponse =
                throw IOException("transient network failure") // not a 401
        }
        val authenticator = PiggyCardsAuthenticator(tokenApi, config)

        val result = authenticator.reLogin()

        assertNull(result)
        // Transient failure must NOT wipe the cached token: the session survives and retries.
        assertEquals("existing-token", config.getSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN))
    }

    @Test
    fun reLogin_onGenuine401_clearsToken() = runBlocking {
        val config = realConfig()
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_USER_ID, "user-1")
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_PASSWORD, "pw-1")
        config.setSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN, "existing-token")

        val tokenApi = object : PiggyCardsTokenApi {
            override suspend fun login(loginRequest: LoginRequest): LoginResponse =
                throw CTXSpendException("unauthorized", errorCode = 401) // genuine credential rejection
        }
        val authenticator = PiggyCardsAuthenticator(tokenApi, config)

        val result = authenticator.reLogin()

        assertNull(result)
        // A genuine 401 means the credentials are no longer valid, so the cached token is cleared.
        assertEquals("", config.getSecuredData(PiggyCardsConfig.PREFS_KEY_ACCESS_TOKEN))
    }
}