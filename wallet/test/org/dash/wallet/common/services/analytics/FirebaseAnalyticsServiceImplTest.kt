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
import io.mockk.verifyOrder
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
    private val store: MutableMap<String, Any?> = java.util.concurrent.ConcurrentHashMap()
    private val disk: MutableMap<String, Any?> = java.util.concurrent.ConcurrentHashMap()
    @Volatile private var commitSucceeds = true
    // Runs inside every commit(), before the outcome is decided (memory already updated).
    @Volatile private var duringCommit: () -> Unit = {}
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
            duringCommit()
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

    @Test
    fun midWrite_isEnabledReportsTheSavedValue_notTheUnconfirmedOne() {
        // commit() puts the new value in memory before the disk write's outcome is known.
        // A reader on another thread (logEvent, a reopened screen) must not see it.
        configuration.setAnalyticsEnabled(false)
        commitSucceeds = false
        val writer = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)
        val reader = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)
        var seenMidWrite: Boolean? = null
        duringCommit = {
            seenMidWrite = reader.isEnabled
            reader.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())
        }

        assertFalse(writer.setEnabled(true))

        assertEquals("an opt-in still being written is not yet saved", false, seenMidWrite)
        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }
        assertFalse(reader.isEnabled)
    }

    @Test
    fun concurrentWrites_fromTwoInstances_runOneAtATime_andTheLastToFinishWins() {
        val first = service()
        val second = service()
        val firstInCommit = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val commits = java.util.Collections.synchronizedList(mutableListOf<String>())
        duringCommit = {
            val name = Thread.currentThread().name
            commits += "start $name"
            if (name == "first") {
                firstInCommit.countDown()
                releaseFirst.await()
            }
            commits += "end $name"
        }
        var firstResult: Boolean? = null
        var secondResult: Boolean? = null
        val firstThread = Thread({ firstResult = first.setEnabled(false) }, "first")
        val secondThread = Thread({ secondResult = second.setEnabled(true) }, "second")

        firstThread.start()
        firstInCommit.await()
        secondThread.start()
        // The second write must wait on the process-wide lock, not reach commit().
        while (secondThread.state != Thread.State.BLOCKED) {
            assertTrue("second write finished while the first held the lock", secondThread.isAlive)
            Thread.yield()
        }
        assertEquals(listOf("start first"), commits.toList())
        releaseFirst.countDown()
        firstThread.join()
        secondThread.join()

        assertEquals(listOf("start first", "end first", "start second", "end second"), commits.toList())
        assertEquals(true, firstResult)
        assertEquals(true, secondResult)
        assertTrue("memory", configuration.analyticsEnabled)
        assertEquals("disk", true, disk[Configuration.PREFS_KEY_ANALYTICS_ENABLED])
        assertTrue(first.isEnabled)
        assertTrue(second.isEnabled)
        assertFalse(session.unsavedOptOut)
        verifyOrder {
            firebaseAnalytics.setAnalyticsCollectionEnabled(false)
            firebaseAnalytics.setAnalyticsCollectionEnabled(true)
        }
    }

    @Test
    fun concurrentWrites_aFailedOptOutFinishingLast_keepsCollectionOff() {
        val optIn = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)
        val optOut = FirebaseAnalyticsServiceImpl(configuration, session, isDebug = false)
        configuration.setAnalyticsEnabled(false)
        val optInInCommit = java.util.concurrent.CountDownLatch(1)
        val releaseOptIn = java.util.concurrent.CountDownLatch(1)
        duringCommit = {
            if (Thread.currentThread().name == "optIn") {
                optInInCommit.countDown()
                releaseOptIn.await()
            } else {
                commitSucceeds = false
            }
        }
        val optInThread = Thread({ optIn.setEnabled(true) }, "optIn")
        var optOutResult: Boolean? = null
        val optOutThread = Thread({ optOutResult = optOut.setEnabled(false) }, "optOut")

        optInThread.start()
        optInInCommit.await()
        optOutThread.start()
        while (optOutThread.state != Thread.State.BLOCKED) {
            assertTrue(optOutThread.isAlive)
            Thread.yield()
        }
        releaseOptIn.countDown()
        optInThread.join()
        optOutThread.join()

        assertEquals(false, optOutResult)
        // The opt-in saved; the opt-out after it failed: on is what's saved, collection is off.
        assertTrue("memory", configuration.analyticsEnabled)
        assertEquals("disk", true, disk[Configuration.PREFS_KEY_ANALYTICS_ENABLED])
        assertTrue(session.unsavedOptOut)
        verifyOrder {
            firebaseAnalytics.setAnalyticsCollectionEnabled(true)
            firebaseAnalytics.setAnalyticsCollectionEnabled(false)
        }
        optIn.logEvent(AnalyticsConstants.Home.NAV_HOME, mapOf())
        verify(exactly = 0) { firebaseAnalytics.logEvent(any(), any()) }
    }
}
