package de.schildbach.wallet.ui.explore

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentExploreBinding
import org.dash.wallet.common.ui.viewBinding

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
