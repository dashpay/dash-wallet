package org.dash.wallet.features.exploredash.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentSearchBinding
import org.dash.wallet.features.exploredash.ui.adapter.MerchantsAtmsResultAdapter

@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.toolbar.title = "Merchants" // TODO
        binding.titleBar.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

//        binding.testBtn.setOnClickListener {
//            viewModel.event.postValue("test event call")
//            SearchFragmentDirections.searchToDetails("Test Argument")
//        }

        val adapter = MerchantsAtmsResultAdapter { id, viewHolder ->
            Log.i("MERCHANTS", id?.toString() ?: "null id")
        }
        viewModel.searchResults.observe(viewLifecycleOwner, adapter::submitList)
        val decorator = ListDividerDecorator(
            ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!, false)
        binding.searchResultsList.addItemDecoration(decorator)
        binding.searchResultsList.adapter = adapter

        viewModel.init()
    }
}
