package de.schildbach.wallet.ui.more.connections

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ConnectLinkManifestTest {
    @Test fun `exported Connect entry point ignores caller supplied keep unlocked`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("dash-key:abc?n=d&v=2"))
            .putExtra(de.schildbach.wallet.ui.LockScreenActivity.INTENT_EXTRA_KEEP_UNLOCKED, true)
        val controller = org.robolectric.Robolectric.buildActivity(de.schildbach.wallet.ui.main.MainActivity::class.java, intent)
        assertTrue(context.packageManager.getActivityInfo(controller.get().componentName, 0).exported)
        assertFalse(controller.get().keepUnlocked)
    }

    @Test fun `exported payment entry point also ignores keep unlocked`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("dash:X"))
            .putExtra(de.schildbach.wallet.ui.LockScreenActivity.INTENT_EXTRA_KEEP_UNLOCKED, true)
        val controller = org.robolectric.Robolectric.buildActivity(de.schildbach.wallet.ui.send.SendCoinsActivity::class.java, intent)
        assertFalse(controller.get().keepUnlocked)
    }

    @Test fun `Android resolves both opaque QR link schemes to exported MainActivity`() {
        val context = RuntimeEnvironment.getApplication()
        listOf("dash-key:abc?n=d&v=2", "dash-st:abc?n=d&v=1").forEach { link ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(context.packageName)
            val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(link, matches.any { it.activityInfo.name == "de.schildbach.wallet.ui.main.MainActivity" &&
                it.activityInfo.exported })
        }
    }
}
