package org.dash.wallet.common.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.NetworkUnavailableFragmentBinding

class NetworkUnavailableFragment(val messageId: Int = 0, val buttonTextId: Int = 0) : Fragment(R.layout.network_unavailable_fragment) {

    private lateinit var viewModel: NetworkUnavailableFragmentViewModel
    companion object {
        fun newInstance(messageId: Int = R.string.network_unavailable_check_connection, buttonTextId: Int = 0): NetworkUnavailableFragment {
            return NetworkUnavailableFragment(messageId, buttonTextId)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[NetworkUnavailableFragmentViewModel::class.java]
        //use message id
        val binding = NetworkUnavailableFragmentBinding.bind(view)
        if (messageId != 0) {
            binding.networkErrorSubtitle.text = getString(messageId)
        }
        if (buttonTextId != 0) {
            binding.button.isVisible = true
            binding.button.text = getString(buttonTextId)
            binding.button.setOnClickListener {
                viewModel.clickButton.call()
            }
        }
    }
}