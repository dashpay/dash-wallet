package org.dash.wallet.features.exploredash.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.ExploreDashMainInfoBinding

class ExploreDashInfoDialog : DialogFragment(R.layout.explore_dash_main_info){

    private var _binding: ExploreDashMainInfoBinding? = null
    private val binding get() = _binding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ExploreDashMainInfoBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.closeDialogBtn?.setOnClickListener { dismissAllowingStateLoss() }
        binding?.learnMoreLabel?.setOnClickListener {
            BuyGiftCardDescriptionDialog.newInstance().show(parentFragmentManager, "BuyGiftCardDescriptionDialog")
            dismissAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.apply {
            setCancelable(false)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window?.setBackgroundDrawableResource(R.drawable.top_round_corner_bgd_gray_white)
        }
    }

    companion object {
        fun newInstance() = ExploreDashInfoDialog()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}