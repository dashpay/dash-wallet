/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.ui.*
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentMoreBinding
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import javax.inject.Inject

@AndroidEntryPoint
class MoreFragment : Fragment(R.layout.fragment_more) {
    private val binding by viewBinding(FragmentMoreBinding::bind)
    @Inject lateinit var analytics: AnalyticsService
    @Inject lateinit var packageInfoProvider: PackageInfoProvider
    @Inject lateinit var configuration: Configuration
    @Inject lateinit var walletData: WalletDataProvider
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
            analytics.logEvent(AnalyticsConstants.MoreMenu.BUY_SELL, mapOf())
            safeNavigate(MoreFragmentDirections.moreToBuySell())
        }
        binding.explore.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.EXPLORE, mapOf())
            findNavController().navigate(
                R.id.exploreFragment,
                bundleOf(),
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_bottom)
                    .build()
            )
        }
        binding.security.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.SECURITY, mapOf())
            safeNavigate(MoreFragmentDirections.moreToSecurity())
        }
        binding.settings.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.SETTINGS, mapOf())
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.tools.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.TOOLS, mapOf())
            findNavController().navigate(
                R.id.toolsFragment,
                bundleOf(),
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_bottom)
                    .build()
            )
        }
        binding.contactSupport.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.CONTACT_SUPPORT, mapOf())
            val alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(
                requireActivity(),
                packageInfoProvider,
                configuration,
                walletData.wallet,
                walletApplication
            ).buildAlertDialog()
            (requireActivity() as LockScreenActivity).alertDialog = alertDialog
            alertDialog.show()
        }
    }
}
