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
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.dash.wallet.common.ui.LockScreenHost
import org.dash.wallet.common.ui.OutcomeDelivery
import org.dash.wallet.common.ui.awaitDestroyed
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.handleWhenResumed
import org.dash.wallet.common.ui.showNowReplacing
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
 * A send's outcome that needs a dialog (a failure, the minimum-balance question, a transfer's
 * result) must not be taken while the screen is stopped (AdaptiveDialog.showAsync shows nothing
 * unless resumed) nor while the lock screen is up (its answer could send while locked). It stays
 * retained and is shown once the screen is resumed and unlocked, from outside the
 * FragmentManager's lifecycle dispatch, where a synchronous dialog commit throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class HandleWhenResumedTest {

    class HostActivity : FragmentActivity(), LockScreenHost {
        override var lockScreenDisplayed = false

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.My_Theme)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
        }
    }

    /** The retained outcome, kept across recreation as the payment view models keep it. */
    class Outcomes : ViewModel() {
        val outcomes = MutableStateFlow<String?>(null)
    }

    /** A send screen: each outcome asks the user, as the failure and leftover-balance dialogs do. */
    open class Screen : Fragment() {
        private val state by viewModels<Outcomes>()
        val outcomes: MutableStateFlow<String?> get() = state.outcomes
        val answers = mutableListOf<Boolean?>()

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
            FrameLayout(inflater.context)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            handleWhenResumed(
                outcomes,
                unlocked,
                { outcomes.compareAndSet(it, null) },
                { outcomes.compareAndSet(null, it) }
            ) { show(it, this) }
        }

        /** Whether the user answered, as the screens report it. */
        protected open suspend fun show(outcome: String, delivery: OutcomeDelivery): Boolean {
            val answer = AdaptiveDialog.simple(outcome, "Close").showAsync(requireActivity())
            answers += answer
            return answer != null
        }
    }

    /** Shows its result as the Coinbase transfer screen does: a synchronous commit (showNow). */
    class ShowNowScreen : Screen() {
        override suspend fun show(outcome: String, delivery: OutcomeDelivery): Boolean {
            AdaptiveDialog.simple(outcome, "Close").showNow(parentFragmentManager, RESULT_TAG)
            return true
        }
    }

    /**
     * The Coinbase transfer result's shape: a showNow dialog awaited until it is destroyed, whose
     * button only calls a listener the screen sets (lost when the dialog is restored).
     */
    class ResultDialog : DialogFragment() {
        var onClose: (() -> Unit)? = null

        fun answer() {
            onClose?.invoke()
            dismiss()
        }
    }

    open class AwaitedResultScreen : Screen() {
        override suspend fun show(outcome: String, delivery: OutcomeDelivery): Boolean {
            var answered = false
            val dialog = ResultDialog()
            dialog.onClose = {
                answered = true
                delivery.markAnswered()
                answers += true
                onClose()
            }
            dialog.showNowReplacing(parentFragmentManager, RESULT_TAG)
            dialog.awaitDestroyed()
            return answered
        }

        protected open fun onClose() {}
    }

    /** Close leaves the screen, as the transfer's success Close pops back home, before the dialog is gone. */
    class LeavingResultScreen : AwaitedResultScreen() {
        override fun onClose() {
            parentFragmentManager.beginTransaction().remove(this).commitNow()
        }
    }

    /** The minimum-balance question's shape: showAsync under its own tag. */
    class QuestionScreen : Screen() {
        override suspend fun show(outcome: String, delivery: OutcomeDelivery): Boolean {
            val answer = AdaptiveDialog.simple(outcome, "Close", "Continue").showAsync(requireActivity(), QUESTION_TAG)
            answers += answer
            return answer != null
        }
    }

    companion object {
        private const val CONTAINER_ID = 0x7f0f1235
        private const val RESULT_TAG = "result"
        private const val QUESTION_TAG = "question"
        val unlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    private val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
    private var activity = controller.get()
    private var screen: Screen = Screen().also {
        activity.supportFragmentManager.beginTransaction().add(CONTAINER_ID, it).commitNow()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dialogs(): List<AdaptiveDialog> =
        activity.supportFragmentManager.fragments.filterIsInstance<AdaptiveDialog>()

    private fun dialog(): AdaptiveDialog? = dialogs().singleOrNull()

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

    @Test
    fun anOutcomeWhileStopped_thenAResumeUnderTheLockScreen_waitsForTheUnlock() {
        idle()
        controller.pause().stop()
        screen.outcomes.value = "leftover balance"
        idle()

        // Auto-logout locked the wallet meanwhile: the screen resumes under the PIN screen.
        activity.lockScreenDisplayed = true
        controller.start().resume()
        idle()

        assertEquals("not taken while locked", "leftover balance", screen.outcomes.value)
        assertNull("nothing shown over the lock screen", dialog())

        activity.lockScreenDisplayed = false
        unlocked.tryEmit(Unit)
        idle()

        assertNull("taken once unlocked", screen.outcomes.value)
        assertNotNull("and shown", dialog())
    }

    @Test
    fun anOutcomeWhileResumedButLocked_waitsForTheUnlock() {
        idle()
        activity.lockScreenDisplayed = true

        screen.outcomes.value = "send failed"
        idle()

        assertEquals("send failed", screen.outcomes.value)
        assertNull(dialog())

        activity.lockScreenDisplayed = false
        unlocked.tryEmit(Unit)
        idle()

        assertNotNull(dialog())
    }

    @Test
    fun aResultRetainedAcrossRecreation_isShownOnce_withASynchronousCommit_withoutCrashing() {
        activity.supportFragmentManager.beginTransaction().remove(screen).commitNow()
        screen = ShowNowScreen().also {
            activity.supportFragmentManager.beginTransaction().add(CONTAINER_ID, it).commitNow()
        }
        idle()
        controller.pause().stop()
        // The view model's send finishes while the screen is stopped, before the recreation.
        screen.outcomes.value = "transfer sent"
        idle()

        controller.recreate()
        idle()
        activity = controller.get()
        screen = activity.supportFragmentManager.findFragmentById(CONTAINER_ID) as Screen

        assertNull("taken by the recreated screen", screen.outcomes.value)
        assertEquals("shown once", 1, dialogs().count { it.tag == RESULT_TAG && it.showsDialog })
    }

    /** What LockScreenActivity does when it locks: shows the PIN screen, then dismisses every dialog. */
    private fun lock() {
        activity.lockScreenDisplayed = true
        dialogs().forEach { it.dismissAllowingStateLoss() }
        idle()
    }

    private fun unlock() {
        activity.lockScreenDisplayed = false
        unlocked.tryEmit(Unit)
        idle()
    }

    @Test
    fun aQuestionTheLockDismissesUnanswered_isAskedAgainOnUnlock_andAnsweredOnce() {
        screen.outcomes.value = "leftover balance"
        idle()
        assertNotNull(dialog())

        lock()

        assertNull("nothing over the lock screen", dialog())
        assertEquals("given back, not lost", "leftover balance", screen.outcomes.value)

        unlock()

        val again = requireNotNull(dialog()) { "asked again once unlocked" }
        again.requireView().findViewById<View>(org.dash.wallet.common.R.id.dialog_negative_button).performClick()
        idle()

        assertEquals(listOf(null, false), screen.answers)
        assertNull("answered: not given back", screen.outcomes.value)
        assertNull(dialog())
    }

    @Test
    fun aQuestionTheUserDismisses_isNotAskedAgain() {
        screen.outcomes.value = "leftover balance"
        idle()

        requireNotNull(dialog()).dismissAllowingStateLoss()
        idle()

        assertNull(screen.outcomes.value)
        assertNull(dialog())
    }

    private fun resultDialog(): ResultDialog? =
        activity.supportFragmentManager.findFragmentByTag(RESULT_TAG) as? ResultDialog

    private fun useScreen(replacement: Screen) {
        activity.supportFragmentManager.beginTransaction().remove(screen).commitNow()
        screen = replacement.also {
            activity.supportFragmentManager.beginTransaction().add(CONTAINER_ID, it).commitNow()
        }
        idle()
    }

    @Test
    fun aResultTheLockDismisses_isShownAgainOnUnlock_andAnAnsweredOneIsNot() {
        useScreen(AwaitedResultScreen())
        screen.outcomes.value = "transfer sent"
        idle()
        assertNotNull(resultDialog())

        activity.lockScreenDisplayed = true
        requireNotNull(resultDialog()).dismissAllowingStateLoss()
        idle()

        assertNull(resultDialog())
        assertEquals("given back, not lost", "transfer sent", screen.outcomes.value)

        unlock()

        requireNotNull(resultDialog()) { "shown again once unlocked" }.answer()
        idle()

        assertNull("answered: not given back", screen.outcomes.value)
        assertNull(resultDialog())
    }

    private fun recreate() {
        controller.recreate()
        idle()
        activity = controller.get()
        screen = activity.supportFragmentManager.findFragmentById(CONTAINER_ID) as Screen
    }

    private fun resultDialogs(): List<ResultDialog> =
        activity.supportFragmentManager.fragments.filterIsInstance<ResultDialog>()

    @Test
    fun aResultUpAtRecreation_isReplaced_andItsCloseReachesTheRecreatedScreen() {
        useScreen(AwaitedResultScreen())
        screen.outcomes.value = "transfer sent"
        idle()
        assertNotNull(resultDialog())

        recreate()

        assertNull("taken again by the recreated screen", screen.outcomes.value)
        val shown = resultDialogs()
        assertEquals("the restored copy replaced, not doubled", 1, shown.size)

        shown.single().answer()
        idle()

        assertEquals("Close reached the recreated screen", listOf(true), screen.answers)
        assertNull("answered: not given back", screen.outcomes.value)
        assertEquals(0, resultDialogs().size)
    }

    @Test
    fun aQuestionUpAtRecreation_isReplaced_andItsAnswerReachesTheRecreatedScreen_once() {
        useScreen(QuestionScreen())
        screen.outcomes.value = "leftover balance"
        idle()
        assertNotNull(dialog())

        recreate()

        assertNull("taken again by the recreated screen", screen.outcomes.value)
        val shown = dialogs().filter { it.tag == QUESTION_TAG }
        assertEquals("the restored copy replaced, not doubled", 1, shown.size)

        shown.single().requireView().findViewById<View>(org.dash.wallet.common.R.id.dialog_positive_button).performClick()
        idle()

        assertEquals("Continue reached the recreated screen, once", listOf<Boolean?>(true), screen.answers)
        assertNull("answered: not given back", screen.outcomes.value)
        assertNull(dialog())
    }

    @Test
    fun aResultAnswered_whoseCloseLeavesTheScreenBeforeTheDialogIsGone_isNotGivenBack() {
        useScreen(LeavingResultScreen())
        val outcomes = screen.outcomes
        outcomes.value = "transfer sent"
        idle()

        requireNotNull(resultDialog()).answer()
        idle()

        assertEquals(listOf(true), screen.answers)
        assertNull("answered before the screen went: not given back", outcomes.value)
        assertEquals(0, resultDialogs().size)
    }
}
