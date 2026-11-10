package de.schildbach.wallet.ui.more.connections

import android.app.Application
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ConnectionDialogLockTest {
    @Test fun `pending callback is deferred while locked and survives relocking the visible dialog`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val locked = MutableStateFlow(true)
        val dialogs = mutableListOf<org.dash.wallet.common.ui.dialogs.AdaptiveDialog>()
        val result = async {
            awaitUnlockedConnectionDialog(activity, activity, locked, { !locked.value }) {
                connectionCallbackDialog(activity, "example.org").also { dialogs += it }
            }
        }
        try {
            runCurrent()
            assertTrue(dialogs.isEmpty())
            locked.value = false
            runCurrent()
            assertEquals(1, dialogs.size)
            assertTrue(dialogs.last().requireDialog().isShowing)
            val staleButton = dialogs.first().requireView().findViewById<android.view.View>(
                org.dash.wallet.common.R.id.dialog_positive_button)
            locked.value = true
            runCurrent()
            assertFalse(dialogs.first().dialog?.isShowing == true)
            staleButton.performClick()
            assertFalse(result.isCompleted)
            locked.value = false
            runCurrent()
            assertEquals(2, dialogs.size)
            controller.pause().stop()
            runCurrent()
            assertFalse(dialogs.last().dialog?.isShowing == true)
            assertFalse(result.isCompleted)
            controller.start().resume()
            runCurrent()
            assertEquals(3, dialogs.size)
            dialogs.last().requireView().findViewById<android.view.View>(
                org.dash.wallet.common.R.id.dialog_positive_button).performClick()
            runCurrent()
            assertEquals(true, result.await())
        } finally {
            result.cancel()
            controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    /** Owns the screen's ViewModel, as ConnectionsFragment does for the approve sheet and its child sheets. */
    class ScreenHost : androidx.fragment.app.Fragment() {
        lateinit var model: ConnectionsViewModel
        override val defaultViewModelProviderFactory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = model as T
        }
    }

    @Test fun `the expiry date picker goes down with the approve sheet when the wallet locks`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val request = ConnectionRequest("App", "", "app", "alice", "identity", blockTime = System.currentTimeMillis())
        val screen = ScreenHost().apply {
            model = io.mockk.mockk(relaxed = true) {
                io.mockk.every { uiState } returns MutableStateFlow(ConnectionsUIState(pendingRequest = request))
            }
        }
        try {
            activity.supportFragmentManager.beginTransaction().add(screen, "screen").commitNow()
            // Stands in for the approve sheet, which the lock path dismisses with its child sheets.
            val approveSheet = androidx.fragment.app.DialogFragment()
            approveSheet.showNow(screen.childFragmentManager, "approve")
            val picker = de.schildbach.wallet.ui.compose_views.ConnectLimitPickerDialog
                .newInstance(de.schildbach.wallet.ui.compose_views.ConnectLimit.EXPIRATION)
            picker.showNow(approveSheet.childFragmentManager, "picker")
            picker.showDatePicker(request.blockTime, null)
            val datePicker = checkNotNull(picker.expiryDatePicker)
            assertTrue(datePicker.isShowing)
            approveSheet.dismissAllowingStateLoss()
            screen.childFragmentManager.executePendingTransactions()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(datePicker.isShowing)
            assertNull(picker.expiryDatePicker)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun `details arriving after auto lock are withheld until unlock`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val locked = MutableStateFlow(false)
        val details = kotlinx.coroutines.CompletableDeferred<String>()
        var dialog: org.dash.wallet.common.ui.dialogs.AdaptiveDialog? = null
        val result = async {
            val text = details.await()
            awaitUnlockedConnectionDialog(activity, activity, locked, { !locked.value }) {
                connectionCallbackDialog(activity, text).also { dialog = it }
            }
        }
        try {
            runCurrent()
            locked.value = true
            details.complete("example.org")
            runCurrent()
            assertNull(dialog)
            assertFalse(result.isCompleted)
            locked.value = false
            runCurrent()
            assertTrue(dialog!!.requireDialog().isShowing)
            result.cancel()
            runCurrent()
            assertFalse(dialog!!.dialog?.isShowing == true)
        } finally {
            result.cancel()
            controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }
}
