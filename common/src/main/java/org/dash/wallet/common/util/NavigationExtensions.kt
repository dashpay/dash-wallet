/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.common.util

import android.net.Uri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.navigation.NavDirections
import androidx.navigation.fragment.DialogFragmentNavigator
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.findNavController

// Avoids "Navigation action/destination xyz cannot be found from the current destination" errors
fun Fragment.safeNavigate(directions: NavDirections) {
    val navController = findNavController()

    if (navController.currentDestination is FragmentNavigator.Destination) {
        val destination = navController.currentDestination as FragmentNavigator.Destination

        if (javaClass.name == destination.className) {
            navController.navigate(directions)
        }
    }
}

fun DialogFragment.dialogSafeNavigate(directions: NavDirections) {
    val navController = findNavController()

    if (navController.currentDestination is DialogFragmentNavigator.Destination) {
        val destination = navController.currentDestination as DialogFragmentNavigator.Destination

        if (javaClass.name == destination.className) {
            navController.navigate(directions)
        }
    }
}

sealed class DeepLinkDestination(val deepLink: Uri) {
    object ReceiveDash : DeepLinkDestination(Uri.parse("${Constants.DEEP_LINK_PREFIX}/payments/0"))
    object SendDash : DeepLinkDestination(Uri.parse("${Constants.DEEP_LINK_PREFIX}/payments/1"))
    data class Transaction(val txId: String) :
        DeepLinkDestination(Uri.parse("${Constants.DEEP_LINK_PREFIX}/transactions/$txId"))
    data class Exchange(val exchange: String, val action: String) :
        DeepLinkDestination(
            Uri.parse("${Constants.DEEP_LINK_PREFIX}/exchange/$exchange/$action")
        )
}

fun Fragment.deepLinkNavigate(destination: DeepLinkDestination) {
    findNavController().navigate(destination.deepLink)
}

fun Fragment.goBack() {
    findNavController().popBackStack()
}
