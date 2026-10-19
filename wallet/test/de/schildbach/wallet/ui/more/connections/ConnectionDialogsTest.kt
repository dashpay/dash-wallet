package de.schildbach.wallet.ui.more.connections

import android.app.Application
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ConnectionDialogsTest {
    @Test fun `callback uses shared wallet dialog and cancel does not open the website`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        var opened = false
        val dialog = connectionCallbackDialog(activity, "example.org")
        try {
            dialog.show(activity) { opened = it == true }
            shadowOf(Looper.getMainLooper()).idle()
            activity.supportFragmentManager.executePendingTransactions()
            assertTrue(dialog.requireDialog().isShowing)
            assertFalse(opened)
            dialog.requireView().findViewById<android.view.View>(org.dash.wallet.common.R.id.dialog_negative_button).performClick()
            assertFalse(opened)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun `budget details reuse the shared wallet dialog`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val dialog = connectionDetailsDialog(activity, "App", "0.5 DASH remaining")
        try {
            dialog.showNow(activity.supportFragmentManager, "connection-details")
            assertTrue(dialog.requireDialog().isShowing)
            assertEquals("0.5 DASH remaining", dialog.requireView()
                .findViewById<android.widget.TextView>(org.dash.wallet.common.R.id.dialog_message).text.toString())
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }
}
