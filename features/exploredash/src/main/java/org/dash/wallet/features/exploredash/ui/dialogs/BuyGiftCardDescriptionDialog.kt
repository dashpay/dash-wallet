package org.dash.wallet.features.exploredash.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.BuyGiftCardDescriptionBinding

class BuyGiftCardDescriptionDialog : DialogFragment(R.layout.buy_gift_card_description) {

    private var _binding: BuyGiftCardDescriptionBinding? = null
    private val binding get() = _binding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = BuyGiftCardDescriptionBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.closeDialogBtn?.setOnClickListener { dismissAllowingStateLoss() }
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
        fun newInstance() =  BuyGiftCardDescriptionDialog()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}