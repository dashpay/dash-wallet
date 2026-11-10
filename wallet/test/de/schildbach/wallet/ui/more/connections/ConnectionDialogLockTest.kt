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
