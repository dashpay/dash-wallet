package de.schildbach.wallet.ui.more.connections

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
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

    @Test fun `identity mismatch message names the requested and the wallet identity`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val message = identityMismatchMessage(context,
            ConnectionsViewModel.ScanOutcome.IdentityMismatch("RequestedId", "WalletId", "alice"))
        assertTrue(message.contains("RequestedId"))
        assertTrue(message.contains("alice (WalletId)"))
        val noUsername = identityMismatchMessage(context,
            ConnectionsViewModel.ScanOutcome.IdentityMismatch("RequestedId", "WalletId", null))
        assertTrue(noUsername.contains("WalletId"))
        assertFalse(noUsername.contains("(WalletId)"))
    }

    class ViewFragment : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
            View(inflater.context)
    }

    @Test fun `identity mismatch returns after a pause once both the host and the fragment view resume`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val fragment = ViewFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val viewLifecycle = fragment.viewLifecycleOwner.lifecycle
        assertNotSame(activity.lifecycle, viewLifecycle)
        val hostStatesAtViewResume = mutableListOf<Lifecycle.State>()
        viewLifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hostStatesAtViewResume += activity.lifecycle.currentState
        })
        val mismatch = ConnectionsViewModel.ScanOutcome.IdentityMismatch("RequestedId", "WalletId", "alice")
        val locked = MutableStateFlow(false)
        val shown = mutableListOf<ConnectionsViewModel.ScanOutcome.IdentityMismatch?>()
        val job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            identityMismatchToShow(MutableStateFlow(mismatch), activity.lifecycle, viewLifecycle, locked) { true }
                .collect { shown += it }
        }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(mismatch, shown.last())
            hostStatesAtViewResume.clear()

            controller.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shown.last())

            controller.resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            assertEquals(Lifecycle.State.RESUMED, viewLifecycle.currentState)
            assertEquals(listOf(Lifecycle.State.STARTED), hostStatesAtViewResume)
            assertEquals(mismatch, shown.last())

            locked.value = true
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shown.last())

            locked.value = false
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(mismatch, shown.last())
        } finally {
            job.cancel()
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

    @Test fun `Active rows disconnect while Disconnected and Expired rows reconnect`() {
        assertEquals(ConnectionRowAction.DISCONNECT, ConnectionsTab.ACTIVE.rowAction)
        assertEquals(ConnectionRowAction.RECONNECT, ConnectionsTab.DISCONNECTED.rowAction)
        assertEquals(ConnectionRowAction.RECONNECT, ConnectionsTab.EXPIRED.rowAction)
    }
}
