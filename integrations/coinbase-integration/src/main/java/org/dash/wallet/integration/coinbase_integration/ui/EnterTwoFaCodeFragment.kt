package org.dash.wallet.integration.coinbase_integration.ui

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.View
import androidx.activity.addCallback
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.EnterTwoFaCodeFragmentBinding
import org.dash.wallet.integration.coinbase_integration.viewmodels.EnterTwoFaCodeViewModel

@AndroidEntryPoint
class EnterTwoFaCodeFragment : Fragment(R.layout.enter_two_fa_code_fragment) {

    private val binding by viewBinding(EnterTwoFaCodeFragmentBinding::bind)
    private val viewModel by viewModels<EnterTwoFaCodeViewModel>()

    companion object {
        fun newInstance() = EnterTwoFaCodeFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPress()
    }


    private fun handleBackPress() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner){
            findNavController().popBackStack()
        }
    }
}