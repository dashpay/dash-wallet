package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUserNamePrivacyBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class CreateUsernamePrivacyFragment : Fragment(R.layout.fragment_user_name_privacy) {
    private val binding by viewBinding(FragmentUserNamePrivacyBinding::bind)
    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private var selectedCoinJoinMode = CoinJoinMode.BASIC
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setPrivacyItemSelected(binding.basicCl, binding.bacicIcon)
        binding.basicCl.setOnClickListener {
            setPrivacyItemSelected(binding.basicCl, binding.bacicIcon)
            setPrivacyItemUnSelected(binding.intermediateCl, binding.intermediateIcon)
            setPrivacyItemUnSelected(binding.advancedCl, binding.advancedIcon)
            selectedCoinJoinMode = CoinJoinMode.BASIC
        }

        binding.intermediateCl.setOnClickListener {
            if (!dashPayViewModel.isWifiConnected()) {
                showConnectionWaringDialog(CoinJoinMode.INTERMEDIATE)
            } else {
                setIntermediateMode(CoinJoinMode.INTERMEDIATE)
            }
        }

        binding.advancedCl.setOnClickListener {
            if (!dashPayViewModel.isWifiConnected()) {
                showConnectionWaringDialog(CoinJoinMode.ADVANCED)
            } else {
                setAdvancedMode(CoinJoinMode.ADVANCED)
            }
        }
        binding.continueBtn.setOnClickListener {
            dashPayViewModel.logEvent(
                AnalyticsConstants.CoinJoinPrivacy.USERNAME_PRIVACY_BTN_CONTINUE,
                bundleOf(
                    AnalyticsConstants.Parameters.VALUE to selectedCoinJoinMode.name,
                ),
            )

            dashPayViewModel.setCoinJoinMode(selectedCoinJoinMode)
            findNavController().previousBackStackEntry?.savedStateHandle?.set(
                "mode",
                selectedCoinJoinMode,
            )
            findNavController().popBackStack()
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().previousBackStackEntry?.savedStateHandle?.set("mode", null)
            findNavController().popBackStack()
        }
    }

    private fun showConnectionWaringDialog(mode: CoinJoinMode) {
        AdaptiveDialog.create(
            org.dash.wallet.integration.coinbase_integration.R.drawable.ic_warning,
            getString(
                if (mode == CoinJoinMode.INTERMEDIATE) {
                    R.string.Intermediate_level_WIFI_Warning
                } else {
                    R.string.Advanced_privacy_level_requires_a_reliable_internet_connection
                },
            ),
            getString(R.string.privcay_level_WIFI_warning_desc),
            getString(org.dash.wallet.integration.coinbase_integration.R.string.cancel),
            getString(R.string.continue_anyway),
        ).show(requireActivity()) {
            if (it == true) {
                dashPayViewModel.logEvent(AnalyticsConstants.CoinJoinPrivacy.USERNAME_PRIVACY_WIFI_BTN_CONTINUE)
                if (mode == CoinJoinMode.INTERMEDIATE) {
                    setIntermediateMode(mode)
                } else if (mode == CoinJoinMode.ADVANCED) {
                    setAdvancedMode(mode)
                }
            } else {
                dashPayViewModel.logEvent(AnalyticsConstants.CoinJoinPrivacy.USERNAME_PRIVACY_WIFI_BTN_CANCEL)
            }
        }
    }

    private fun setIntermediateMode(mode: CoinJoinMode) {
        setPrivacyItemUnSelected(binding.basicCl, binding.bacicIcon)
        setPrivacyItemSelected(binding.intermediateCl, binding.intermediateIcon)
        setPrivacyItemUnSelected(binding.advancedCl, binding.advancedIcon)
        selectedCoinJoinMode = mode
    }

    private fun setAdvancedMode(mode: CoinJoinMode) {
        setPrivacyItemUnSelected(binding.basicCl, binding.bacicIcon)
        setPrivacyItemUnSelected(binding.intermediateCl, binding.intermediateIcon)
        setPrivacyItemSelected(binding.advancedCl, binding.advancedIcon)
        selectedCoinJoinMode = mode
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
