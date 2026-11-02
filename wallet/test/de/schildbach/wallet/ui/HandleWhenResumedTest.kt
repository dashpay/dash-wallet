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
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.handleWhenResumed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A send's outcome that needs a dialog (a failure, the minimum-balance question) must not be
 * taken while the screen is stopped: AdaptiveDialog.showAsync shows nothing unless resumed, so
 * the outcome would be lost. It stays retained and is shown once the screen resumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class HandleWhenResumedTest {

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.My_Theme)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
        }
    }

    /** A send screen: each outcome asks the user, as the failure and leftover-balance dialogs do. */
    class Screen : Fragment() {
        val outcomes = MutableStateFlow<String?>(null)
        val answers = mutableListOf<Boolean?>()

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
            FrameLayout(inflater.context)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            handleWhenResumed(outcomes, { outcomes.compareAndSet(it, null) }) { outcome ->
                answers += AdaptiveDialog.simple(outcome, "Close").showAsync(requireActivity())
            }
        }
    }

    companion object {
        private const val CONTAINER_ID = 0x7f0f1235
    }

    private val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
    private val activity = controller.get()
    private val screen = Screen().also {
        activity.supportFragmentManager.beginTransaction().add(CONTAINER_ID, it).commitNow()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dialog(): AdaptiveDialog? =
        activity.supportFragmentManager.fragments.filterIsInstance<AdaptiveDialog>().singleOrNull()

    @Test
    fun anOutcomeWhileStopped_isKept_andShownOnResume() {
        idle()
        controller.pause().stop()

        screen.outcomes.value = "send failed"
        idle()

        assertEquals("not taken while stopped", "send failed", screen.outcomes.value)
        assertNull(dialog())

        controller.start().resume()
        idle()

        assertNull("taken once resumed", screen.outcomes.value)
        assertNotNull("and shown", dialog())
    }

    @Test
    fun anOutcomeWhileStartedButNotResumed_isKept_andShownOnResume() {
        idle()
        controller.pause()

        screen.outcomes.value = "leftover balance"
        idle()

        assertEquals("not taken while paused", "leftover balance", screen.outcomes.value)
        assertNull(dialog())

        controller.resume()
        idle()

        assertNotNull(dialog())
    }

    @Test
    fun aPauseWhileItsDialogIsUp_doesNotLoseTheAnswer() {
        screen.outcomes.value = "leftover balance"
        idle()
        val shown = requireNotNull(dialog())

        controller.pause()
        idle()
        controller.resume()
        idle()

        shown.requireView().findViewById<View>(org.dash.wallet.common.R.id.dialog_negative_button).performClick()
        idle()

        assertEquals(1, screen.answers.size)
        assertNotNull("the button's answer, not a dismissal", screen.answers.single())
        assertNull(dialog())
    }
}
