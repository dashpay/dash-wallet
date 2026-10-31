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
import org.robolectric.Shadows.shadowOf
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

    @Test fun `in app quick send uses private entry point and preserves launch parameters`() {
        val context = RuntimeEnvironment.getApplication()
        val caller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        val payment = org.dash.wallet.common.data.PaymentIntent.fromAddress(
            "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL", null, org.bitcoinj.params.TestNet3Params.get())
        de.schildbach.wallet.ui.send.SendCoinsActivity.start(caller, "internal-send", payment, true, true)
        val launched = shadowOf(caller).nextStartedActivity
        assertEquals(de.schildbach.wallet.ui.send.InAppSendCoinsActivity::class.java.name, launched.component!!.className)
        assertFalse(context.packageManager.getActivityInfo(launched.component!!, 0).exported)
        assertEquals("internal-send", launched.action)
        assertNotNull(launched.getParcelableExtra<org.dash.wallet.common.data.PaymentIntent>(
            de.schildbach.wallet.ui.send.SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT))
        assertTrue(launched.getBooleanExtra(de.schildbach.wallet.ui.send.SendCoinsActivity.INTENT_EXTRA_IS_QUICK_SCAN, false))
        val controller = org.robolectric.Robolectric.buildActivity(de.schildbach.wallet.ui.send.InAppSendCoinsActivity::class.java, launched)
        assertTrue(controller.get().keepUnlocked)
    }

    @Test fun `private send does not grant keep unlocked by default or for ordinary sends and credit purchases`() {
        val caller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        val controller = org.robolectric.Robolectric.buildActivity(de.schildbach.wallet.ui.send.InAppSendCoinsActivity::class.java)
        assertFalse(controller.get().keepUnlocked)
        de.schildbach.wallet.ui.send.SendCoinsActivity.start(caller, null)
        val send = shadowOf(caller).nextStartedActivity
        assertEquals(de.schildbach.wallet.ui.send.InAppSendCoinsActivity::class.java.name, send.component!!.className)
        assertFalse(send.getBooleanExtra(de.schildbach.wallet.ui.LockScreenActivity.INTENT_EXTRA_KEEP_UNLOCKED, false))
        de.schildbach.wallet.ui.send.SendCoinsActivity.startBuyCredits(caller)
        val credits = shadowOf(caller).nextStartedActivity
        assertEquals(de.schildbach.wallet.ui.send.InAppSendCoinsActivity::class.java.name, credits.component!!.className)
        assertTrue(credits.getBooleanExtra(de.schildbach.wallet.ui.send.SendCoinsActivity.INTENT_EXTRA_BUY_CREDITS, false))
        assertFalse(credits.getBooleanExtra(de.schildbach.wallet.ui.LockScreenActivity.INTENT_EXTRA_KEEP_UNLOCKED, false))
    }

    @Test fun `payment URLs still resolve only to the exported send activity`() {
        val context = RuntimeEnvironment.getApplication()
        for (scheme in listOf("dash", "darkcoin", "pay")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme:X"))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
            val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(matches.any { it.activityInfo.name == de.schildbach.wallet.ui.send.SendCoinsActivity::class.java.name &&
                it.activityInfo.exported })
            assertFalse(matches.any { it.activityInfo.name == de.schildbach.wallet.ui.send.InAppSendCoinsActivity::class.java.name })
        }
    }

    @Test fun `dashpay connect resolves to MainActivity while dashpay invite stays with the invite handler`() {
        val context = RuntimeEnvironment.getApplication()
        fun matches(link: String) = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY
        ).filter { it.activityInfo.exported }.map { it.activityInfo.name }

        listOf("dashpay://connect?v=2&n=d&r=AAAA", "dashpay://connect/?v=2&n=d&r=AAAA").forEach { link ->
            assertTrue(link, matches(link).contains("de.schildbach.wallet.ui.main.MainActivity"))
            assertFalse(link, matches(link).contains("de.schildbach.wallet.ui.InviteHandlerActivity"))
        }
        val invite = matches("dashpay://invite?du=alice&assetlocktx=a0&pk=b&islock=c")
        assertTrue(invite.contains("de.schildbach.wallet.ui.InviteHandlerActivity"))
        assertFalse(invite.contains("de.schildbach.wallet.ui.main.MainActivity"))
        // the dash-key/dash-st filter must keep matching host-less opaque URIs
        assertTrue(matches("dash-key:abc?n=d&v=2").contains("de.schildbach.wallet.ui.main.MainActivity"))
        assertTrue(matches("dash-st:abc?n=d&v=1").contains("de.schildbach.wallet.ui.main.MainActivity"))
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
