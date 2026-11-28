/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.dash.wallet.common.ui.LockScreenHost
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.PaymentOutcomeUnknownWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The outcome-unknown warning: only the user's Close leaves the screen. The lock screen dismisses
 * every dialog on lock or auto-logout; that must keep the screen (and its terminal state) and
 * bring the warning back on unlock or the next resume, never over the lock screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class PaymentOutcomeUnknownWarningTest {

    class HostActivity : FragmentActivity(), LockScreenHost {
        override var lockScreenDisplayed = false

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.My_Theme)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
        }
    }

    /** The screen's terminal flag, kept in saved state as the payment view models keep it. */
    class ScreenState(private val savedState: SavedStateHandle) : ViewModel() {
        val outcomeUnknown: StateFlow<Boolean> = savedState.getStateFlow(KEY, false)

        fun markOutcomeUnknown() {
            savedState[KEY] = true
        }

        companion object {
            const val KEY = "payment_outcome_unknown"
        }
    }

    class PaymentScreen : Fragment() {
        private val state by viewModels<ScreenState>()
        val outcomeUnknown: StateFlow<Boolean> get() = state.outcomeUnknown
        val unlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var closes = 0
        var blocked = false

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
            FrameLayout(inflater.context)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            PaymentOutcomeUnknownWarning(this) { closes++ }
                .keepShownWhile(outcomeUnknown, unlocked) { blocked = true }
        }

        fun markOutcomeUnknown() = state.markOutcomeUnknown()
    }

    companion object {
        private const val CONTAINER_ID = 0x7f0f1234
    }

    private val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
    private var activity = controller.get()
    private var screen = PaymentScreen().also {
        activity.supportFragmentManager.beginTransaction().add(CONTAINER_ID, it).commitNow()
    }

    /** All the dialogs under the warning's tag, restored or shown. */
    private fun taggedWarnings() = activity.supportFragmentManager.fragments
        .filter { it.tag == PaymentOutcomeUnknownWarning.TAG }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun warning(): AdaptiveDialog? =
        activity.supportFragmentManager.findFragmentByTag(PaymentOutcomeUnknownWarning.TAG) as? AdaptiveDialog

    /** What LockScreenActivity.notifyAndDismissFragments does to every dialog when it locks. */
    private fun lock() {
        activity.lockScreenDisplayed = true
        warning()?.dismissAllowingStateLoss()
        idle()
    }

    private fun clickClose(dialog: AdaptiveDialog) {
        val close = dialog.requireView().findViewById<View>(org.dash.wallet.common.R.id.dialog_negative_button)
        close.performClick()
        idle()
    }

    @Test
    fun noWarning_untilTheOutcomeIsUnknown() {
        idle()
        assertNull(warning())

        screen.markOutcomeUnknown()
        idle()

        assertNotNull(warning())
        assertTrue(screen.blocked)
        assertFalse(warning()?.isCancelable ?: true)
    }

    @Test
    fun lockDismissesTheWarning_theScreenStays_andItIsShownAgainOnUnlock() {
        screen.markOutcomeUnknown()
        idle()
        val first = warning()
        assertNotNull(first)

        lock()

        assertEquals("a dismissal by the lock screen is not the user's Close", 0, screen.closes)
        assertFalse(activity.isFinishing)
        assertNull("not shown over the lock screen", warning())

        activity.lockScreenDisplayed = false
        screen.unlocked.tryEmit(Unit)
        idle()

        val again = warning()
        assertNotNull("shown again once unlocked", again)
        assertNotSame(first, again)

        // And its Close still works: the user's explicit acknowledgement leaves the screen.
        clickClose(again!!)
        assertEquals(1, screen.closes)
    }

    @Test
    fun lockDismissesTheWarning_andItIsShownAgainOnTheNextResume() {
        screen.markOutcomeUnknown()
        idle()

        lock()
        controller.pause()
        activity.lockScreenDisplayed = false // e.g. unlocked by the time the screen resumes
        controller.resume()
        idle()

        assertNotNull(warning())
        assertEquals(0, screen.closes)
    }

    @Test
    fun aResumeWhileLocked_doesNotShowTheWarningOverTheLockScreen() {
        screen.markOutcomeUnknown()
        idle()

        lock()
        controller.pause()
        controller.resume()
        idle()

        assertNull(warning())
        assertEquals(0, screen.closes)
    }

    @Test
    fun explicitClose_leavesTheScreen_once() {
        screen.markOutcomeUnknown()
        idle()

        clickClose(requireNotNull(warning()))

        assertEquals(1, screen.closes)
        assertNull(warning())
    }

    @Test
    fun recreation_keepsTheFlag_replacesTheRestoredWarning_andCloseExitsTheNewScreenOnce() {
        screen.markOutcomeUnknown()
        idle()
        val before = requireNotNull(warning())
        val oldScreen = screen

        // A configuration change: the FragmentManager restores the screen and the warning, but
        // the restored warning has lost its Close action.
        controller.recreate()
        idle()
        activity = controller.get()
        screen = activity.supportFragmentManager.findFragmentById(CONTAINER_ID) as PaymentScreen

        assertNotSame(oldScreen, screen)
        assertTrue("the terminal flag survives the recreation", screen.outcomeUnknown.value)
        assertTrue(screen.blocked)
        assertEquals("exactly one warning, the restored copy replaced", 1, taggedWarnings().size)
        val after = requireNotNull(warning())
        assertNotSame(before, after)
        assertEquals("no exit without the user's Close", 0, screen.closes + oldScreen.closes)

        clickClose(after)

        assertEquals("Close exits the recreated screen once", 1, screen.closes)
        assertEquals(0, oldScreen.closes)
        assertTrue(taggedWarnings().isEmpty())
    }

    @Test
    fun onlyAButtonResult_isAnExplicitClose() {
        assertTrue(PaymentOutcomeUnknownWarning.isExplicitClose(false))
        assertTrue(PaymentOutcomeUnknownWarning.isExplicitClose(true))
        assertFalse(PaymentOutcomeUnknownWarning.isExplicitClose(null))
    }
}
