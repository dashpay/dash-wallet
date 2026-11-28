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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.TaggedProgressDialog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A screen that shows a blocking progress dialog while its view model sends (the Coinbase
 * transfer screen and conversion preview). A recreation while the send is held in flight must
 * not leave a second dialog, nor the FragmentManager-restored one blocking the screen after the
 * send ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class TaggedProgressDialogTest {

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.My_Theme)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
        }
    }

    /** The send's loading state, retained across the recreation as the view model is. */
    class SendState : ViewModel() {
        val loading = MutableLiveData<Boolean>()
    }

    class SendingScreen : Fragment() {
        private val state by activityViewModels<SendState>()
        private val progress = TaggedProgressDialog({ parentFragmentManager })

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
            FrameLayout(inflater.context)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            state.loading.observe(viewLifecycleOwner) { loading ->
                if (loading) progress.show("loading") else progress.hide()
            }
        }
    }

    companion object {
        private const val CONTAINER_ID = 0x7f0f1235
    }

    private val controller = Robolectric.buildActivity(HostActivity::class.java).setup()

    init {
        controller.get().supportFragmentManager.beginTransaction().add(CONTAINER_ID, SendingScreen()).commitNow()
    }

    private val activity get() = controller.get()
    private val loading get() = ViewModelProvider(activity)[SendState::class.java].loading

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun progressDialogs() = activity.supportFragmentManager.fragments
        .filter { it.tag == TaggedProgressDialog.DEFAULT_TAG }

    @Test
    fun recreatedWhileSending_oneProgressDialog_andNoneOnceTheSendEnds() {
        loading.value = true
        idle()
        assertEquals(1, progressDialogs().size)

        // A configuration change while the send is held in flight: the FragmentManager restores
        // the dialog, and the new screen sees "loading" again.
        controller.recreate()
        idle()
        assertEquals("exactly one progress dialog while sending", 1, progressDialogs().size)

        loading.value = false
        idle()
        assertEquals("none left blocking the screen once the send ends", 0, progressDialogs().size)
    }

    @Test
    fun theSendEndsWhileTheScreenIsRecreated_theRestoredDialogIsRemoved() {
        loading.value = true
        idle()
        assertEquals(1, progressDialogs().size)

        // The send ends while the screen is not observing (stopped for the recreation): the new
        // screen's first value is "done", and the restored dialog must not stay.
        controller.pause().stop()
        loading.value = false
        controller.recreate()
        idle()

        assertEquals(0, progressDialogs().size)
    }

    @Test
    fun loadingTwice_stillOneDialog() {
        loading.value = true
        idle()
        loading.value = true
        idle()

        assertEquals(1, progressDialogs().size)
    }
}
