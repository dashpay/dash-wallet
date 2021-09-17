package org.dash.wallet.features.exploredash

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.databinding.FragmentExploreBinding

class ExploreFragment : Fragment(R.layout.fragment_explore) {
    private val binding by viewBinding(FragmentExploreBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.toolbar.title = "Explore"
        binding.titleBar.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.merchantsBtn.setOnClickListener {
            findNavController().navigate(ExploreFragmentDirections.exploreToMerchants())
        }

        binding.atmsBtn.setOnClickListener {
            findNavController().navigate(ExploreFragmentDirections.exploreToAtms("Test Argument"))
        }
    }
}
