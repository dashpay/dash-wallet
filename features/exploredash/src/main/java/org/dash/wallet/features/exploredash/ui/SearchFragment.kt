package org.dash.wallet.features.exploredash.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentSearchBinding
import org.dash.wallet.features.exploredash.ui.adapter.MerchantsAtmsResultAdapter
import org.dash.wallet.features.exploredash.ui.dialog.TerritoryFilterDialog

@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarTitle.text = getString(R.string.explore_where_to_spend)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

//        binding.testBtn.setOnClickListener {
//            viewModel.event.postValue("test event call")
//            SearchFragmentDirections.searchToDetails("Test Argument")
//        }

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

            false
        }

        binding.clearBtn.setOnClickListener {
            binding.search.text.clear()
        }

        binding.filterBtn.setOnClickListener {
            TerritoryFilterDialog().show(parentFragmentManager, "territory_filter")
        }

        val adapter = MerchantsAtmsResultAdapter { id, _ ->
            Log.i("MERCHANTS", id?.toString() ?: "null id")
        }

        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(divider, false, R.layout.group_header)
        binding.searchResultsList.addItemDecoration(decorator)
        binding.searchResultsList.adapter = adapter

        viewModel.searchResults.observe(viewLifecycleOwner, adapter::submitList)
        viewModel.filterMode.observe(viewLifecycleOwner) {
            binding.allOption.isChecked = it == ExploreViewModel.FilterMode.All
            binding.physicalOption.isChecked = it == ExploreViewModel.FilterMode.Physical
            binding.onlineOption.isChecked = it == ExploreViewModel.FilterMode.Online
        }

        viewModel.initData()
    }
}
