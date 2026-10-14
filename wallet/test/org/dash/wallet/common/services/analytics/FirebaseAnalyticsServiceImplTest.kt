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

    // SharedPreferences keeps an in-memory map that commit() updates even when the
    // disk write fails; `store` is that memory, `disk` what survives a restart.
    private val store = mutableMapOf<String, Any?>()
    private val disk = mutableMapOf<String, Any?>()
    private var commitSucceeds = true
    private var session = AnalyticsSessionState()
    private lateinit var configuration: Configuration
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        editor = mockk(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers {
            store[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.commit() } answers {
            if (commitSucceeds) {
                disk.clear()
                disk.putAll(store)
            }
            commitSucceeds
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

    /** Process restart: memory reloads from disk. */
    private fun restart() {
        store.clear()
        store.putAll(disk)
        session = AnalyticsSessionState()
    }

    /** A new instance in the same process, e.g. a reopened Settings screen. */
    private fun service() = FirebaseAnalyticsServiceImpl(configuration, session)

    @Test
    fun analyticsIsOnByDefault() {
        assertTrue(service().isEnabled)
    }

    @Test
    fun turningOff_persistsTheChoice_andStopsFirebaseCollection() {
        val service = service()

        service.setEnabled(false)

        assertEquals(false, store[Configuration.PREFS_KEY_ANALYTICS_ENABLED])
        assertFalse(configuration.analyticsEnabled)
        assertFalse("a new instance must see the persisted choice", service().isEnabled)
        verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
        verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
    }

    @Test
    fun turningBackOn_resumesFirebaseCollection() {
        configuration.setAnalyticsEnabled(false)
        val service = service()

        service.setEnabled(true)

        assertTrue(configuration.analyticsEnabled)
        verify { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
        verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
    }

    @Test
    fun whenOff_logEventNeverReachesFirebase() {
        configuration.setAnalyticsEnabled(false)
        val service = service()

        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())
        service.logEvent(
            AnalyticsConstants.Home.TRANSACTION_FILTER,
            mapOf(AnalyticsConstants.Parameter.VALUE to "sent")
        )

        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }
    }

    @Test
    fun releaseBuild_whenOn_logEventReachesFirebase() {
        val service = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)

        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())

        verify(exactly = 1) { firebaseAnalytics.logEvent(AnalyticsConstants.Home.NAV_HOME, any()) }
    }

    @Test
    fun releaseBuild_whenOff_logEventNeverReachesFirebase() {
        configuration.setAnalyticsEnabled(false)
        val service = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)

        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())

        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }
    }

    @Test
    fun releaseBuild_turningOffAfterLogging_stopsForwarding() {
        val service = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)
        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())

        service.setEnabled(false)
        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())

        verify(exactly = 1) { firebaseAnalytics.logEvent(any(), any()) }
    }

    @Test
    fun configuredFirebaseApp_isAvailable() {
        assertTrue(service().isAvailable)
    }

    @Test
    fun placeholderFirebaseApp_isNotAvailable_andNeverReachesFirebase() {
        every { firebaseOptions.projectId } returns AnalyticsService.PLACEHOLDER_FIREBASE_PROJECT_ID
        val service = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)

        assertFalse(service.isAvailable)
        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())
        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }
    }

    @Test
    fun uninitializedFirebaseApp_isNotAvailable() {
        every { FirebaseApp.getInstance() } throws IllegalStateException("Default FirebaseApp is not initialized")
        try {
            assertFalse(service().isAvailable)
        } finally {
            every { FirebaseApp.getInstance() } returns firebaseApp
        }
    }

    @Test
    fun walletReset_keepsTheOptOut_inASingleCommit() {
        configuration.setAnalyticsEnabled(false)
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

    @Test
    fun resolvingFirebase_doesNotTouchTheCollectionSetting() {
        // A late-resolving instance must not overwrite a newer opt-out made through another one.
        val background = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)
        service().setEnabled(false)
        clearMocks(firebaseAnalytics, answers = false)
        every { firebaseOptions.projectId } returns "dash-wallet-real-project"

        assertTrue(background.isAvailable)

        verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(any()) }
    }

    @Test
    fun optOut_whoseWriteFails_keepsTheSavedValue_butStopsCollectionThisProcess() {
        commitSucceeds = false
        val service = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)

        assertFalse("the failure must be reported", service.setEnabled(false))

        assertTrue("isEnabled reports what is saved", service.isEnabled)
        assertTrue("memory agrees with disk", configuration.analyticsEnabled)
        verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
        service.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())
        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }
    }

    @Test
    fun failedOptOut_survivesAReopenedScreen_andItsRetrySurvivesARestart() {
        commitSucceeds = false
        service().setEnabled(false)

        // Settings closed and reopened: a new instance, same process.
        clearMocks(firebaseAnalytics, answers = false)
        val reopened = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)
        assertTrue("the switch shows the saved on, so toggling it is a retry", reopened.isEnabled)
        reopened.applyStoredSetting()
        verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
        reopened.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())
        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }

        commitSucceeds = true
        assertTrue(reopened.setEnabled(false))
        assertFalse(session.unsavedOptOut)

        restart()
        assertFalse("a saved retry survives a restart", service().isEnabled)
    }

    @Test
    fun optOut_whoseWriteFailsAndIsNeverRetried_isLostOnRestart() {
        // The limitation the "Couldn't turn off" toast warns about.
        commitSucceeds = false
        service().setEnabled(false)

        restart()
        val afterRestart = service()
        assertTrue(afterRestart.isEnabled)
        afterRestart.applyStoredSetting()
        verify { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
    }

    @Test
    fun optIn_whoseWriteFails_isReported_andReadsBackAsOff() {
        configuration.setAnalyticsEnabled(false)
        commitSucceeds = false
        val service = service()

        assertFalse(service.setEnabled(true))

        assertFalse("an unsaved opt-in must not read back as on", service.isEnabled)
        verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
        verify(exactly = 0) { firebaseAnalytics.setAnalyticsCollectionEnabled(true) }
    }

    @Test
    fun applyStoredSetting_appliesWithoutWriting() {
        configuration.setAnalyticsEnabled(false)
        clearMocks(editor, answers = false)

        service().applyStoredSetting()

        verify { firebaseAnalytics.setAnalyticsCollectionEnabled(false) }
        verify(exactly = 0) { editor.commit() }
    }
}
