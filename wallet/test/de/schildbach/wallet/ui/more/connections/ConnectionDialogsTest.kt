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

    @Test fun `legacy entries explicitly offer local removal`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val dialog = connectionDetailsDialog(activity, "Old app", "Removing this entry does not revoke access.", ConnectionStatus.APPROVED, legacyOnly = true)
        try {
            dialog.showNow(activity.supportFragmentManager, "legacy-details")
            assertTrue(dialog.requireDialog().isShowing)
            assertTrue(dialog.requireView().findViewById<android.widget.TextView>(org.dash.wallet.common.R.id.dialog_message).text.contains("does not revoke"))
            assertEquals("Remove from list", dialog.requireView()
                .findViewById<android.widget.TextView>(org.dash.wallet.common.R.id.dialog_positive_button).text.toString())
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun `budget details reuse the shared wallet dialog`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val dialog = connectionDetailsDialog(activity, "App", "0.5 DASH remaining", ConnectionStatus.ACTIVE)
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

    @Test fun `disconnected details offer scanning instead of revocation`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val dialog = connectionDetailsDialog(activity, "App", "Keys 4 and 5 disabled", ConnectionStatus.DISCONNECTED)
        var accepted = false
        try {
            dialog.show(activity) { accepted = it == true }
            shadowOf(Looper.getMainLooper()).idle()
            activity.supportFragmentManager.executePendingTransactions()
            val button = dialog.requireView().findViewById<android.widget.TextView>(
                org.dash.wallet.common.R.id.dialog_positive_button)
            assertEquals(activity.getString(de.schildbach.wallet_test.R.string.dash_connect_scan_qr), button.text.toString())
            assertFalse(dialog.requireArguments().containsKey(
                org.dash.wallet.common.ui.dialogs.AdaptiveDialog.POS_BUTTON_COLOR_ARG))
            button.performClick()
            assertTrue(accepted)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun `active and pending details still offer revocation`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        try {
            listOf(ConnectionStatus.ACTIVE, ConnectionStatus.APPROVED).forEach { status ->
                val dialog = connectionDetailsDialog(activity, "App", "Grant details", status)
                dialog.showNow(activity.supportFragmentManager, "details-$status")
                assertEquals(activity.getString(de.schildbach.wallet_test.R.string.dash_connect_revoke), dialog.requireView()
                    .findViewById<android.widget.TextView>(org.dash.wallet.common.R.id.dialog_positive_button).text.toString())
                dialog.dismissNow()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
