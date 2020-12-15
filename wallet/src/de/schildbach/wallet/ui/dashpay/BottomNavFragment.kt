/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay

import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import de.schildbach.wallet.ui.MainActivity
import kotlinx.android.synthetic.main.activity_main.*

abstract class BottomNavFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {

    private val mainActivity by lazy {
        requireActivity() as MainActivity
    }

    abstract val navigationItemId: Int
    private val navigationItem by lazy {
        mainActivity.bottom_navigation.menu.findItem(navigationItemId)
    }

    override fun onResume() {
        super.onResume()
        // select the right button in bottom nav
        navigationItem.isChecked = true
    }
}
