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

    @Test fun `tabs keep pending with active and legacy records with disconnected`() {
        fun connection(status: ConnectionStatus, legacy: Boolean = false) =
            DAppConnection("app", "App", "", status, 0L, legacyOnly = legacy)
        assertTrue(ConnectionsTab.ACTIVE.includes(connection(ConnectionStatus.ACTIVE)))
        assertTrue(ConnectionsTab.ACTIVE.includes(connection(ConnectionStatus.APPROVED)))
        assertTrue(ConnectionsTab.DISCONNECTED.includes(connection(ConnectionStatus.DISCONNECTED)))
        assertTrue(ConnectionsTab.DISCONNECTED.includes(connection(ConnectionStatus.APPROVED, legacy = true)))
        assertFalse(ConnectionsTab.ACTIVE.includes(connection(ConnectionStatus.APPROVED, legacy = true)))
        assertTrue(ConnectionsTab.EXPIRED.includes(connection(ConnectionStatus.EXPIRED)))
        ConnectionStatus.entries.forEach { status ->
            assertEquals(1, ConnectionsTab.entries.count { it.includes(connection(status)) })
        }
    }
}
