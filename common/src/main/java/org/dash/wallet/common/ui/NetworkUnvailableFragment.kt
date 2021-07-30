package org.dash.wallet.common.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.NetworkUnavailableFragmentBinding

class NetworkUnavailableFragment(val messageId: Int) : Fragment(R.layout.network_unavailable_fragment) {

    companion object {
        fun newInstance(messageId: Int = R.string.network_unavailable_check_connection): NetworkUnavailableFragment {
            return NetworkUnavailableFragment(messageId)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //use message id
        val binding = NetworkUnavailableFragmentBinding.bind(view)
        if (messageId != 0) {
            binding.networkErrorSubtitle.text = getString(messageId)
        }
    }
}