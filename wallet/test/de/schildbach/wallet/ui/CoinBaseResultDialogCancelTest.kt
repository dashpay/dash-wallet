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
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.integrations.coinbase.ui.dialogs.CoinBaseResultDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A sent payment's success (a Coinbase sale or transfer) is acknowledged only by its button: Back
 * dismissing it would answer nothing, leave the payment marked unanswered and refuse later
 * payments silently. An error stays dismissable with Back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class CoinBaseResultDialogCancelTest {
    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.My_Theme)
            super.onCreate(savedInstanceState)
        }
    }

    private val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun showAndPressBack(
        type: CoinBaseResultDialog.Type,
        answerByButtonOnly: Boolean
    ): CoinBaseResultDialog {
        val dialog = CoinBaseResultDialog.newInstance(type, null, dashToCoinbase = true, answerByButtonOnly = answerByButtonOnly)
        dialog.showNow(activity.supportFragmentManager, "result")
        idle()
        dialog.requireDialog().onBackPressed()
        idle()
        return dialog
    }

    @Test
    fun back_doesNotDismissASaleSuccess() {
        assertTrue(showAndPressBack(CoinBaseResultDialog.Type.CONVERSION_SUCCESS, answerByButtonOnly = true).isAdded)
    }

    @Test
    fun back_doesNotDismissATransferSuccess() {
        assertTrue(showAndPressBack(CoinBaseResultDialog.Type.TRANSFER_DASH_SUCCESS, answerByButtonOnly = true).isAdded)
    }

    @Test
    fun back_stillDismissesAnError() {
        assertFalse(showAndPressBack(CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR, answerByButtonOnly = false).isAdded)
    }

    @Test
    fun back_stillDismissesASuccessShownWithoutTheFlag_asTheTwoFaScreenShowsIt() {
        // EnterTwoFaCodeFragment shows purchase/withdrawal successes and does not rebind a
        // restored copy: it must stay dismissable, or a restored one could never be closed.
        assertFalse(showAndPressBack(CoinBaseResultDialog.Type.DEPOSIT_SUCCESS, answerByButtonOnly = false).isAdded)
        assertFalse(showAndPressBack(CoinBaseResultDialog.Type.CONVERSION_SUCCESS, answerByButtonOnly = false).isAdded)
    }
}
