package org.dash.wallet.features.exploredash.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.dialogSafeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.ExploreDashMainInfoBinding

class ExploreDashInfoDialog : OffsetDialogFragment<ConstraintLayout>(){

    private val binding by viewBinding(ExploreDashMainInfoBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.explore_dash_main_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.learnMoreLabel.setOnClickListener {
            dialogSafeNavigate(ExploreDashInfoDialogDirections.infoToGiftCardDetail())
        }
        binding.exploreDashInfoContinueBtn.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    companion object {
        fun newInstance() = ExploreDashInfoDialog()
    }
}