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

package org.dash.wallet.common.services.analytics

import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.analytics.FirebaseAnalytics
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifySequence
import org.dash.wallet.common.Configuration
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** MO-1065: the Settings analytics opt-out. */
class FirebaseAnalyticsServiceImplTest {
    companion object {
        // The ktx `Firebase.analytics` caches its FirebaseAnalytics in a
        // process-wide field (it is FirebaseAnalytics.getInstance(FirebaseApp
        // .getInstance()...) on first use), so every test shares one mock.
        private val firebaseAnalytics = mockk<FirebaseAnalytics>(relaxed = true)
        private val firebaseOptions = mockk<FirebaseOptions>(relaxed = true)
        private val firebaseApp = mockk<FirebaseApp>(relaxed = true) {
            every { options } returns firebaseOptions
        }

        @JvmStatic
        @BeforeClass
        fun mockFirebase() {
            mockkStatic(FirebaseApp::class, FirebaseAnalytics::class)
            every { FirebaseApp.getInstance() } returns firebaseApp
            every { FirebaseAnalytics.getInstance(any()) } returns firebaseAnalytics
        }

        @JvmStatic
        @AfterClass
        fun unmockFirebase() {
            unmockkStatic(FirebaseApp::class, FirebaseAnalytics::class)
        }
    }

    private val store = mutableMapOf<String, Any?>()
    private lateinit var configuration: Configuration
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        editor = mockk(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers {
            store[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.clear() } answers {
            store.clear()
            editor
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(any(), any()) } answers {
            store[firstArg()] as? Boolean ?: secondArg()
        }
        every { prefs.edit() } returns editor
        configuration = Configuration(prefs)
        clearMocks(firebaseAnalytics, answers = false)
        every { firebaseOptions.projectId } returns "dash-wallet-real-project"
    }

    @Test
    fun analyticsIsOnByDefault() {
        assertTrue(FirebaseAnalyticsServiceImpl(configuration).isEnabled)
    }

    @Test
    fun turningOff_persistsTheChoice_andStopsFirebaseCollection() {
        val service = FirebaseAnalyticsServiceImpl(configuration)

        service.isEnabled = false

        assertEquals(false, store[Configuration.PREFS_KEY_ANALYTICS_ENABLED])
        assertFalse(configuration.analyticsEnabled)
        assertFalse("a new instance must see the persisted choice", FirebaseAnalyticsServiceImpl(configuration).isEnabled)
        verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
        verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
    }

    @Test
    fun turningBackOn_resumesFirebaseCollection() {
        configuration.analyticsEnabled = false
        val service = FirebaseAnalyticsServiceImpl(configuration)

        service.isEnabled = true

        assertTrue(configuration.analyticsEnabled)
        verify { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
        verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
    }

    @Test
    fun whenOff_logEventNeverReachesFirebase() {
        configuration.analyticsEnabled = false
        val service = FirebaseAnalyticsServiceImpl(configuration)

        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())
        service.logEvent(
            AnalyticsConstants.Home.TRANSACTION_FILTER,
            mapOf(AnalyticsConstants.Parameter.VALUE to "sent")
        )

        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }
    }

    @Test
    fun releaseBuild_whenOn_logEventReachesFirebase() {
        val service = FirebaseAnalyticsServiceImpl(configuration, isDebug = false)

        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())

        verify(exactly = 1) { firebaseAnalytics.logEvent(AnalyticsConstants.Home.NAV_HOME, any()) }
    }

    @Test
    fun releaseBuild_whenOff_logEventNeverReachesFirebase() {
        configuration.analyticsEnabled = false
        val service = FirebaseAnalyticsServiceImpl(configuration, isDebug = false)

        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())

        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }
    }

    @Test
    fun releaseBuild_turningOffAfterLogging_stopsForwarding() {
        val service = FirebaseAnalyticsServiceImpl(configuration, isDebug = false)
        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())

        service.isEnabled = false
        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())

        verify(exactly = 1) { firebaseAnalytics.logEvent(any(), any()) }
    }

    @Test
    fun configuredFirebaseApp_isAvailable() {
        assertTrue(FirebaseAnalyticsServiceImpl(configuration).isAvailable)
    }

    @Test
    fun placeholderFirebaseApp_isNotAvailable_andNeverReachesFirebase() {
        every { firebaseOptions.projectId } returns AnalyticsService.PLACEHOLDER_FIREBASE_PROJECT_ID
        val service = FirebaseAnalyticsServiceImpl(configuration, isDebug = false)

        assertFalse(service.isAvailable)
        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())
        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }
    }

    @Test
    fun uninitializedFirebaseApp_isNotAvailable() {
        every { FirebaseApp.getInstance() } throws IllegalStateException("Default FirebaseApp is not initialized")
        try {
            assertFalse(FirebaseAnalyticsServiceImpl(configuration).isAvailable)
        } finally {
            every { FirebaseApp.getInstance() } returns firebaseApp
        }
    }

    @Test
    fun walletReset_keepsTheOptOut_inASingleCommit() {
        configuration.analyticsEnabled = false
        store["some_wallet_pref"] = true
        clearMocks(editor, answers = false)

        configuration.clearPreservingAnalytics()

        assertEquals(mapOf(Configuration.PREFS_KEY_ANALYTICS_ENABLED to false), store)
        // one editor, one synchronous commit: a process death cannot land between
        // the wipe and the re-write of the opt-out
        verifySequence {
            editor.clear()
            editor.putBoolean(Configuration.PREFS_KEY_ANALYTICS_ENABLED, false)
            editor.commit()
        }
    }
}
