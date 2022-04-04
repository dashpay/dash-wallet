package org.dash.wallet.integration.coinbase_integration.ui

import androidx.lifecycle.ViewModelProvider
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.TwoFaAuthenticationFragmentBinding
import org.dash.wallet.integration.coinbase_integration.viewmodels.TwoFaAuththenticationViewModel

@AndroidEntryPoint
class TwoFaAuthenticationFragment : Fragment(R.layout.two_fa_authentication_fragment) {

    private val binding by viewBinding(TwoFaAuthenticationFragmentBinding::bind)
    private val viewModel by viewModels<TwoFaAuththenticationViewModel>()

    companion object {
        fun newInstance() = TwoFaAuthenticationFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

    }
}