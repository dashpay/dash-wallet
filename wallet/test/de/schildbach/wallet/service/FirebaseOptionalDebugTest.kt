package de.schildbach.wallet.service

import android.app.Application
import com.google.firebase.FirebaseApp
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class FirebaseOptionalDebugTest {
    @Test
    fun debugAnalyticsWorksWithoutFirebaseConfiguration() {
        assertTrue(FirebaseApp.getApps(RuntimeEnvironment.getApplication()).isEmpty())
        val analytics = FirebaseAnalyticsServiceImpl()
        analytics.logEvent("local_debug_test", emptyMap())
        analytics.logError(IllegalStateException("test"), "No Firebase configuration")
        assertTrue(FirebaseApp.getApps(RuntimeEnvironment.getApplication()).isEmpty())
    }
}
