/*
 *
 *  * Copyright 2021 Dash Core Group.
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.dash.wallet.features.exploredash.ui.extensions

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.drawable.InsetDrawable
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.dash.wallet.common.Configuration
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogCustomLocationRequestBinding
import org.dash.wallet.features.exploredash.ui.ExploreTopic
import kotlin.coroutines.resume

val Fragment.isLocationPermissionGranted: Boolean
    get() = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
                   Manifest.permission.ACCESS_COARSE_LOCATION
    ).any {
        ActivityCompat.checkSelfPermission(
            requireContext(), it
        ) == PackageManager.PERMISSION_GRANTED
    }

fun Fragment.registerPermissionLauncher(
    onResult: (Boolean) -> Unit
): ActivityResultLauncher<Array<String>> {
    return registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissionResults ->
            onResult.invoke(permissionResults.any { it.value })
        }
}

fun Fragment.requestLocationPermission(
    exploreTopic: ExploreTopic,
    configuration: Configuration,
    requestLauncher: ActivityResultLauncher<Array<String>>
) {
    if (configuration.hasExploreDashLocationDialogBeenShown()) {
        requestLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))
    } else {
        lifecycleScope.launch {
            val result = showPermissionExplainerDialog(exploreTopic)

            if (result == true) {
                configuration.setHasExploreDashLocationDialogBeenShown(true)
                requestLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ))
            }
        }
    }
}

suspend fun Fragment.showPermissionExplainerDialog(exploreTopic: ExploreTopic): Boolean? {
    return suspendCancellableCoroutine { coroutine ->
        val title = if (exploreTopic == ExploreTopic.Merchants) {
            R.string.explore_merchant_location_explainer_title
        } else {
            R.string.explore_atm_location_explainer_title
        }

        val message = if (exploreTopic == ExploreTopic.Merchants) {
            R.string.explore_merchant_location_explainer_message
        } else {
            R.string.explore_atm_location_explainer_message
        }

        val dialog = Dialog(requireContext())
        val binding = DialogCustomLocationRequestBinding.inflate(layoutInflater)
        dialog.setContentView(binding.root)

        binding.title.text = getString(title)
        binding.subtitle.text = getString(message)
        binding.icon.setImageDrawable(ResourcesCompat.getDrawable(resources, R.drawable.ic_location, null))
        binding.okButton.text = getString(R.string.permission_allow)
        binding.cancelButton.text = getString(R.string.permission_deny)

        binding.okButton.setOnClickListener {
            if (coroutine.isActive) {
                coroutine.resume(true)
            }
            dialog.dismiss()
        }

        binding.cancelButton.setOnClickListener {
            if (coroutine.isActive) {
                coroutine.resume(false)
            }
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (coroutine.isActive) {
                coroutine.resume(null)
            }
        }

        val dialogInset = resources.getDimensionPixelOffset(R.dimen.dialog_horizontal_inset)
        dialog.window!!.setBackgroundDrawable(
            InsetDrawable(
                ResourcesCompat.getDrawable(resources, R.drawable.white_background_rounded, null),
                dialogInset, 0, dialogInset, 0
            )
        )
        dialog.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }
}