package org.dash.wallet.features.exploredash.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentExploreBinding

@AndroidEntryPoint
class ExploreFragment : Fragment(R.layout.fragment_explore) {
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val viewModel: ExploreViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.toolbar.title = "Explore"
        binding.titleBar.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.merchantsBtn.setOnClickListener {
            findNavController().navigate(ExploreFragmentDirections.exploreToSearch())
        }

        binding.atmsBtn.setOnClickListener {
            findNavController().navigate(ExploreFragmentDirections.exploreToSearch())
        }

        viewModel.dumbSync()
    }
}
