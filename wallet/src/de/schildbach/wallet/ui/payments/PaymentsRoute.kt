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

package de.schildbach.wallet.ui.payments

import androidx.annotation.AnimRes
import androidx.annotation.IdRes
import androidx.navigation.NavOptions
import de.schildbach.wallet_test.R

/**
 * Where a "payments" entry point (the bottom-nav payments item, the Home Send
 * and Receive shortcuts) goes.
 *
 * @property destinationId the nav destination: [R.id.advancedPaymentsFragment]
 *   in Advanced mode, [R.id.paymentsFragment] otherwise.
 * @property activeTab the logical tab to open ([PaymentsFragment.ACTIVE_TAB_RECEIVE],
 *   [PaymentsFragment.ACTIVE_TAB_PAY], ...), or null to let the screen restore
 *   the most recent one.
 * @property enterAnim / [popExitAnim] the transition for this route, set on the
 *   [NavOptions] rather than in the fragment; null keeps the destination's
 *   existing behaviour (basic mode animates inside [PaymentsFragment]).
 */
data class PaymentsRoute(
    @IdRes val destinationId: Int,
    val activeTab: Int?,
    @AnimRes val enterAnim: Int?,
    @AnimRes val popExitAnim: Int?
) {
    /** Adds this route's transition, if any, to [builder]. */
    fun applyAnimations(builder: NavOptions.Builder): NavOptions.Builder {
        enterAnim?.let { builder.setEnterAnim(it) }
        popExitAnim?.let { builder.setPopExitAnim(it) }
        return builder
    }
}

/**
 * The single routing decision shared by every payments entry point.
 *
 * [advancedMode] is the value held by MainViewModel.advancedMode: false until
 * the setting has been read, and false while a read is failing, so an unknown
 * setting always opens the basic screen.
 */
fun paymentsRoute(advancedMode: Boolean, activeTab: Int? = null): PaymentsRoute =
    if (advancedMode) {
        PaymentsRoute(
            destinationId = R.id.advancedPaymentsFragment,
            activeTab = activeTab,
            enterAnim = R.anim.slide_in_bottom,
            popExitAnim = R.anim.slide_out_bottom
        )
    } else {
        PaymentsRoute(
            destinationId = R.id.paymentsFragment,
            activeTab = activeTab,
            enterAnim = null,
            popExitAnim = null
        )
    }

/**
 * Whether [destinationId] is one of the payments screens, i.e. whether the
 * bottom-nav payments item ([R.id.paymentsFragment]) should be shown selected.
 */
fun isPaymentsDestination(@IdRes destinationId: Int): Boolean =
    destinationId == R.id.paymentsFragment || destinationId == R.id.advancedPaymentsFragment
