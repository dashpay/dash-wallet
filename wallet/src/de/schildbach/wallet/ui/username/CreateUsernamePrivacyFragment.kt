package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUserNamePrivacyBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class CreateUsernamePrivacyFragment : Fragment(R.layout.fragment_user_name_privacy) {
    private val binding by viewBinding(FragmentUserNamePrivacyBinding::bind)
    private val dashPayViewModel: DashPayViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setPrivacyItemSelected(binding.basicCl, binding.bacicIcon)
        binding.basicCl.setOnClickListener {
            setPrivacyItemSelected(binding.basicCl, binding.bacicIcon)
            setPrivacyItemUnSelected(binding.intermediateCl, binding.intermediateIcon)
            setPrivacyItemUnSelected(binding.advancedCl, binding.advancedIcon)
        }

        binding.intermediateCl.setOnClickListener {
            showIntermediateWaringDialog()
        }

        binding.advancedCl.setOnClickListener {
            setPrivacyItemUnSelected(binding.basicCl, binding.bacicIcon)
            setPrivacyItemUnSelected(binding.intermediateCl, binding.intermediateIcon)
            setPrivacyItemSelected(binding.advancedCl, binding.advancedIcon)
        }
        binding.continueBtn.setOnClickListener{
            //dashPayViewModel.logEvent()
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

    }


    private fun showIntermediateWaringDialog() {
       AdaptiveDialog.create(
            org.dash.wallet.integration.coinbase_integration.R.drawable.ic_warning,
           getString(R.string.Intermediate_privacy_level_requires_a_reliable_internet_connection),
            getString(R.string.It_is_recommended_to_be_on_a_wifi_network_to_avoid_losing_any_funds),
            getString(org.dash.wallet.integration.coinbase_integration.R.string.cancel),
            getString(R.string.continue_anyway)
        ).show(requireActivity()){
            if(it == true){
                setPrivacyItemUnSelected(binding.basicCl, binding.bacicIcon)
                setPrivacyItemSelected(binding.intermediateCl, binding.intermediateIcon)
                setPrivacyItemUnSelected(binding.advancedCl, binding.advancedIcon)
            }
       }
    }

    private fun setPrivacyItemSelected(cl: ConstraintLayout, icon: ImageView) {
        cl.isSelected = true
        val tintColor = ResourcesCompat.getColor(resources, R.color.dash_blue, null)
        icon.setColorFilter(tintColor)
    }
    private fun setPrivacyItemUnSelected(cl: ConstraintLayout, icon: ImageView) {
        cl.isSelected = false
        val tintColor = ResourcesCompat.getColor(resources, R.color.light_gray, null)
        icon.setColorFilter(tintColor)
    }
}
