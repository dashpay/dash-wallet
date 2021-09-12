package org.dash.wallet.features.exploredash

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.databinding.FragmentExploreBinding

class ExploreFragment : Fragment(R.layout.fragment_explore) {

    companion object {
        @JvmStatic
        fun newInstance(): ExploreFragment {
            return ExploreFragment()
        }
    }

    private val binding by viewBinding(FragmentExploreBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
//        binding.getDashBtn.setOnClickListener {
//
//        }
    }
}
