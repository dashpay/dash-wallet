/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.more

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.*
<<<<<<< HEAD
=======
>>>>>>> master
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentMoreBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import javax.inject.Inject

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class MoreFragment : Fragment(R.layout.fragment_more) {
    private val binding by viewBinding(FragmentMoreBinding::bind)
    @Inject lateinit var analytics: AnalyticsService
    @Inject lateinit var walletApplication: WalletApplication

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough()
        reenterTransition = MaterialFadeThrough()

        binding.appBar.toolbar.title = getString(R.string.more_title)
        binding.appBar.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.buyAndSell.setOnClickListener {
            startBuyAndSellActivity()
        }
        binding.explore.setOnClickListener {
            findNavController().navigate(
                R.id.exploreFragment,
                bundleOf(),
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_bottom)
                    .build()
            )
        }
        binding.security.setOnClickListener {
            startActivity(Intent(requireContext(), SecurityActivity::class.java))
        }
        binding.settings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.tools.setOnClickListener {
            startActivity(Intent(requireContext(), ToolsActivity::class.java))
        }
        binding.contactSupport.setOnClickListener {
            val alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(
                requireActivity(), walletApplication
            ).buildAlertDialog()
            (requireActivity() as LockScreenActivity).alertDialog = alertDialog
            alertDialog.show()
        }
    }

    private fun startBuyAndSellActivity() {
        analytics.logEvent(AnalyticsConstants.MoreMenu.BUY_SELL_MORE, bundleOf())
        safeNavigate(MoreFragmentDirections.moreToBuySell())
    }
}
