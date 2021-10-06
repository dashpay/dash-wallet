/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.features.exploredash.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.databinding.FragmentSearchBinding
import org.dash.wallet.features.exploredash.ui.adapters.MerchantsAtmsResultAdapter
import org.dash.wallet.features.exploredash.ui.dialogs.TerritoryFilterDialog


@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()

    // TODO: re-integrate when the permission request story is ready
//    private lateinit var fusedLocationClient: FusedLocationProviderClient
//    private val permissionRequestLauncher = registerForActivityResult(
//        ActivityResultContracts.RequestPermission()) { isGranted ->
//        if (isGranted) {
//            Log.i("LOCATION", "permission granted")
//        } else {
//            Log.i("LOCATION", "permission NOT granted")
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
//
//        if (ActivityCompat.checkSelfPermission(
//                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            permissionRequestLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
//        }
//    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarTitle.text = getString(R.string.explore_where_to_spend)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.searchTitle.text = "United States" // TODO: use location to resolve

        binding.allOption.setOnClickListener {
            viewModel.setFilterMode(ExploreViewModel.FilterMode.All)
        }

        binding.physicalOption.setOnClickListener {
            viewModel.setFilterMode(ExploreViewModel.FilterMode.Physical)
        }

        binding.onlineOption.setOnClickListener {
            viewModel.setFilterMode(ExploreViewModel.FilterMode.Online)
        }

        binding.search.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            viewModel.submitSearchQuery(text.toString())
        }

        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val inputManager = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.toggleSoftInput(0, 0)
            }

            true
        }

        binding.clearBtn.setOnClickListener {
            binding.search.text.clear()
        }

        binding.filterBtn.setOnClickListener {
            lifecycleScope.launch {
                val territories = viewModel.getTerritoriesWithMerchants()
                TerritoryFilterDialog(territories, viewModel.pickedTerritory) { name, dialog ->
                    lifecycleScope.launch {
                        delay(300)
                        dialog.dismiss()
                        viewModel.pickedTerritory = name
                    }
                }.show(parentFragmentManager, "territory_filter")
            }
        }

        val adapter = MerchantsAtmsResultAdapter { item, _ ->
            if (item is Merchant) {
                viewModel.openMerchantDetails(item)
            }
        }

        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(divider, false, R.layout.group_header)
        binding.searchResultsList.addItemDecoration(decorator)
        binding.searchResultsList.adapter = adapter

        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            binding.noResultsText.isVisible = results.isEmpty()
            adapter.submitList(results)
        }

        viewModel.filterMode.observe(viewLifecycleOwner) {
            binding.allOption.isChecked = it == ExploreViewModel.FilterMode.All
            binding.allOption.isEnabled = it != ExploreViewModel.FilterMode.All
            binding.physicalOption.isChecked = it == ExploreViewModel.FilterMode.Physical
            binding.physicalOption.isEnabled = it != ExploreViewModel.FilterMode.Physical
            binding.onlineOption.isChecked = it == ExploreViewModel.FilterMode.Online
            binding.onlineOption.isEnabled = it != ExploreViewModel.FilterMode.Online
        }

        viewModel.init()
    }
}
