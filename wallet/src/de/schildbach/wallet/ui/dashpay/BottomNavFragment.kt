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

import android.view.View
import androidx.annotation.LayoutRes
import de.schildbach.wallet.ui.MainActivity
import de.schildbach.wallet.ui.widget.KeyboardResponsiveCoordinatorLayout
import kotlinx.android.synthetic.main.activity_main.*
import org.dash.wallet.common.ui.BaseLockScreenFragment

abstract class BottomNavFragment(@LayoutRes contentLayoutId: Int) : BaseLockScreenFragment(contentLayoutId) {

    var forceHideBottomNav: Boolean = false

    private val mainActivity by lazy {
        requireActivity() as? MainActivity
    }

    override fun onResume() {
        super.onResume()
        // select the right button in bottom nav
        showHideBottomNav()
    }

    private fun showHideBottomNav() {
        mainActivity?.let { activity ->
            val navParentView = activity.bottom_navigation.parent.parent
            if (navParentView is KeyboardResponsiveCoordinatorLayout) {
                navParentView.forceHideViewToHide = forceHideBottomNav
            } else {
                activity.bottom_navigation.visibility = if (forceHideBottomNav) View.VISIBLE else View.GONE
            }
        }
    }
}
