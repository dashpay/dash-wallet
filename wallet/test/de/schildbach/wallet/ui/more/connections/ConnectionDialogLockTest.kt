package de.schildbach.wallet.ui.more.connections

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.ui.compose_views.ApproveConnectionDialog
import de.schildbach.wallet.ui.compose_views.ConnectLimitPickerDialog
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogComposeContainerBinding
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.dash.wallet.common.ui.dismissDialogsForLockScreen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

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

    @Test fun `locking with the expiry date picker open closes the approve sheet, its picker and the date picker`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        val blockTime = System.currentTimeMillis()
        // A grant with capabilities, so the approve sheet offers the funding and expiry limits.
        val request = ConnectionRequest(
            "App", "", "app", "alice", "identity", blockTime = blockTime,
            capabilities = listOf(AppCapability(AppCapabilityKind.ACT_IN_APP)),
            expiresAt = blockTime + 90 * AppConnectLimits.DAY
        )
        var unlocked = true
        val screen = ScreenHost().apply {
            model = mockk(relaxed = true) {
                every { uiState } returns MutableStateFlow(ConnectionsUIState(pendingRequest = request))
                every { approveResult } returns MutableStateFlow(ConnectionsViewModel.ApproveResult.Idle)
                every { isWalletUnlocked } answers { unlocked }
            }
        }
        try {
            activity.supportFragmentManager.beginTransaction().add(screen, "screen").commitNow()
            // The real approve sheet, as ConnectionsFragment shows it in its child fragment manager.
            val approveSheet = ApproveConnectionDialog()
            approveSheet.showNow(screen.childFragmentManager, "approve_connection_dialog")
            assertTrue(approveSheet.requireDialog().isShowing)

            // The user taps Change on the Expires row of the approve sheet ...
            val expiresLabel = activity.getString(R.string.dash_connect_expires)
            val changeLabel = activity.getString(R.string.dash_connect_change)
            val approveRoot = semanticsRoot(approveSheet)
            val expires = textNodes(approveRoot, expiresLabel).single()
            val changeExpiry = textNodes(approveRoot, changeLabel).single { change ->
                change.boundsInRoot.top < expires.boundsInRoot.bottom &&
                    change.boundsInRoot.bottom > expires.boundsInRoot.top
            }
            click(changeExpiry)
            approveSheet.childFragmentManager.executePendingTransactions()
            val picker = approveSheet.childFragmentManager.fragments
                .filterIsInstance<ConnectLimitPickerDialog>().single()
            assertTrue(picker.requireDialog().isShowing)

            // ... then Set date in the expiry picker, which opens the date picker.
            click(textNodes(semanticsRoot(picker), activity.getString(R.string.dash_connect_set_date)).single())
            val datePicker = checkNotNull(picker.expiryDatePicker) { "Set date did not open the date picker" }
            assertTrue(datePicker.isShowing)

            // What LockScreenActivity does when the wallet locks.
            unlocked = false
            dismissDialogsForLockScreen(activity.supportFragmentManager)
            screen.childFragmentManager.executePendingTransactions()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(approveSheet.dialog?.isShowing == true)
            assertFalse(approveSheet.isAdded)
            assertFalse(picker.dialog?.isShowing == true)
            assertFalse(picker.isAdded)
            assertFalse(datePicker.isShowing)
            assertNull(picker.expiryDatePicker)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Lays out a compose sheet's content and returns its semantics root. */
    private fun semanticsRoot(sheet: androidx.fragment.app.DialogFragment): SemanticsNode {
        shadowOf(Looper.getMainLooper()).idle()
        val view = DialogComposeContainerBinding.bind(sheet.requireView()).composeContainer
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, 1080, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        return ReflectionHelpers.callInstanceMethod<SemanticsOwner>(view.getChildAt(0), "getSemanticsOwner")
            .rootSemanticsNode
    }

    private fun textNodes(root: SemanticsNode, text: String): List<SemanticsNode> {
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { all(it) }
        return all(root).filter { node ->
            node.config.contains(SemanticsProperties.Text) &&
                node.config[SemanticsProperties.Text].any { it.text == text }
        }
    }

    /** Invokes the click of the node, or of its nearest clickable ancestor (a button's label sits inside it). */
    private fun click(node: SemanticsNode) {
        val clickable = generateSequence(node) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        val action = checkNotNull(clickable.config[SemanticsActions.OnClick].action) { "No click action" }
        assertTrue(action())
        shadowOf(Looper.getMainLooper()).idle()
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

    private fun connection(legacy: Boolean = false) =
        DAppConnection("app", "App", "app.example", ConnectionStatus.ACTIVE, 0L, legacyOnly = legacy)

    private class DisconnectRun {
        val removed = mutableListOf<DAppConnection>()
        val revoked = mutableListOf<DAppConnection>()
        var done: Boolean? = null
    }

    /** [confirm] null expects no confirmation; otherwise taps Disconnect (true) or Cancel (false). */
    private fun runDisconnect(connection: DAppConnection, confirm: Boolean?, lockBeforeConfirm: Boolean = false) =
        DisconnectRun().also { run ->
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val controller = Robolectric.buildActivity(FragmentActivity::class.java)
                controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
                val activity = controller.setup().get()
                val locked = MutableStateFlow(false)
                val result = async {
                    disconnectConnection(connection, activity, activity, locked, { !locked.value },
                        { run.removed += it }, { run.revoked += it })
                }
                try {
                    runCurrent()
                    val dialog = activity.supportFragmentManager.fragments
                        .filterIsInstance<org.dash.wallet.common.ui.dialogs.AdaptiveDialog>().lastOrNull()
                    if (confirm == null) {
                        assertNull("an older connection is removed without a confirmation", dialog)
                    } else {
                        assertNotNull("a revocation is confirmed first", dialog)
                        val button = dialog?.requireView()?.findViewById<android.view.View>(
                            if (confirm) org.dash.wallet.common.R.id.dialog_positive_button
                            else org.dash.wallet.common.R.id.dialog_negative_button
                        )
                        if (lockBeforeConfirm) {
                            locked.value = true
                            runCurrent()
                        }
                        button?.performClick()
                        runCurrent()
                    }
                    if (lockBeforeConfirm) assertFalse(result.isCompleted) else run.done = result.await()
                } finally {
                    result.cancel()
                    controller.pause().stop().destroy()
                    Dispatchers.resetMain()
                }
            }
        }

    @Test fun `disconnect revokes only after the confirmation is accepted`() {
        val target = connection()
        val run = runDisconnect(target, confirm = true)
        assertEquals(true, run.done)
        assertEquals(listOf(target), run.revoked)
        assertTrue(run.removed.isEmpty())
    }

    @Test fun `cancelled disconnect confirmation revokes nothing`() {
        val run = runDisconnect(connection(), confirm = false)
        assertEquals(false, run.done)
        assertTrue(run.revoked.isEmpty() && run.removed.isEmpty())
    }

    @Test fun `confirmation tapped after locking revokes nothing`() {
        val run = runDisconnect(connection(), confirm = true, lockBeforeConfirm = true)
        assertTrue(run.revoked.isEmpty() && run.removed.isEmpty())
    }

    @Test fun `older connection is removed here without revoking or confirming`() {
        val target = connection(legacy = true)
        val run = runDisconnect(target, confirm = null)
        assertEquals(true, run.done)
        assertEquals(listOf(target), run.removed)
        assertTrue(run.revoked.isEmpty())
    }

    @Test fun `locked wallet neither removes nor revokes`() = runTest {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(org.dash.wallet.common.R.style.My_Theme)
        val activity = controller.setup().get()
        try {
            var touched = false
            listOf(connection(), connection(legacy = true)).forEach {
                assertFalse(disconnectConnection(it, activity, activity, MutableStateFlow(true), { false },
                    { touched = true }, { touched = true }))
            }
            assertFalse(touched)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
