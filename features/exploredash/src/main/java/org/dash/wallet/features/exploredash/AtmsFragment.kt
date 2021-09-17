package org.dash.wallet.features.exploredash

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.databinding.FragmentAtmsBinding

class AtmsFragment : Fragment(R.layout.fragment_atms) {
    private val binding by viewBinding(FragmentAtmsBinding::bind)
    private val args: AtmsFragmentArgs by navArgs()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.toolbar.title = "ATMs"
        binding.titleBar.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.textView.text = args.testArg
    }
}
