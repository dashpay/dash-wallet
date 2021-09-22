package org.dash.wallet.features.exploredash.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.FragmentMerchantsBinding

@AndroidEntryPoint
class MerchantsFragment : Fragment(R.layout.fragment_merchants) {
    private val binding by viewBinding(FragmentMerchantsBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.toolbar.title = "Merchants"
        binding.titleBar.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.testBtn.setOnClickListener {
            viewModel.event.postValue("test event call")
        }
    }
}
