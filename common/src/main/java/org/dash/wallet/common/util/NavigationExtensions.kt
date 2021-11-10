package org.dash.wallet.common.util

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.navigation.NavDirections
import androidx.navigation.fragment.DialogFragmentNavigator
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.findNavController

// Avoids "Navigation action/destination xyz cannot be found from the current destination" errors
fun Fragment.safeNavigate(directions: NavDirections) {
    val navController = findNavController()
    val destination = navController.currentDestination as FragmentNavigator.Destination

    if (javaClass.name == destination.className) {
        navController.navigate(directions)
    }
}

fun DialogFragment.dialogSafeNavigate(directions: NavDirections) {
    val navController = findNavController()
    val destination = navController.currentDestination as DialogFragmentNavigator.Destination

    if (javaClass.name == destination.className) {
        navController.navigate(directions)
    }
}