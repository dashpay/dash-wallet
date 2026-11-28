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

package org.dash.wallet.features.exploredash

import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.OptionPickerDialog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The filters open this picker for the country and the territory, with options held in memory only. */
@RunWith(RobolectricTestRunner::class)
class OptionPickerRestoreTest {
    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(org.dash.wallet.common.R.style.My_Theme)
            super.onCreate(savedInstanceState)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun pickerOpenWhileTheActivityIsRecreated_closesItselfInsteadOfCrashing() {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        val picker = OptionPickerDialog(
            "Country",
            listOf(IconifiedViewItem("All countries"), IconifiedViewItem("United Kingdom")),
            useCheckMark = true
        ) { _, _, _ -> }
        picker.show(controller.get().supportFragmentManager, PICKER_TAG)
        controller.get().supportFragmentManager.executePendingTransactions()
        idle()
        assertNotNull(controller.get().supportFragmentManager.findFragmentByTag(PICKER_TAG))
        assertTrue(picker.dialog?.isShowing == true)

        controller.recreate()
        idle()

        val fragments = controller.get().supportFragmentManager
        fragments.executePendingTransactions()
        assertNull("a picker restored without its options must close", fragments.findFragmentByTag(PICKER_TAG))
    }

    private companion object {
        const val PICKER_TAG = "picker"
    }
}
