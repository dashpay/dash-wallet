/*
 * Copyright 2021 Dash Core Group
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

package de.schildbach.wallet.ui.invite

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.ui.dashpay.PlatformPaymentConfirmDialog
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentInviteFriendBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class InviteFriendFragment() :
    Fragment(R.layout.fragment_invite_friend) {

    companion object {
        private const val ARG_STARTED_FROM_HISTORY = "started_from_history"
        fun newInstance(startedFromHistory: Boolean = false): InviteFriendFragment {
            val fragment = InviteFriendFragment()
            fragment.arguments = Bundle().apply {
                putBoolean(ARG_STARTED_FROM_HISTORY, startedFromHistory)
            }
            return fragment
        }
    }

    private lateinit var binding: FragmentInviteFriendBinding

    private lateinit var walletApplication: WalletApplication

    private val viewModel: InvitationFragmentViewModel by activityViewModels()

    private lateinit var loadingDialog: FancyAlertDialog

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentInviteFriendBinding.bind(view)

        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        val appCompatActivity = requireActivity() as AppCompatActivity
        appCompatActivity.setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().finish() }
        toolbar.title = getString(R.string.invitation_init_title)

        val actionBar = appCompatActivity.supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        walletApplication = requireActivity().application as WalletApplication
        binding.createInvitationButton.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Invites.INVITE_FRIEND)
            safeNavigate(InviteFriendFragmentDirections.inviteFriendFragmentToUsernamePrivacy())
        }

        findNavController().currentBackStackEntry?.savedStateHandle?.getLiveData<CoinJoinMode?>("mode")?.observe(
            viewLifecycleOwner,
        ) { result ->
            // Do something with the result.
            result?.let {
                showConfirmationDialog()
            }
        }
        initViewModel()
    }

    private fun initViewModel() {
        val startedByHistory = arguments?.getBoolean("startedFromHistory") ?: false
        val platformPaymentConfirmDialogViewModel = ViewModelProvider(requireActivity())[PlatformPaymentConfirmDialog.SharedViewModel::class.java]
        platformPaymentConfirmDialogViewModel.clickConfirmButtonEvent.observe(
            viewLifecycleOwner,
        ) {
            confirmButtonClick(startedByHistory)
        }
    }

    private fun confirmButtonClick(startedByHistory: Boolean) {
        showProgress()
        viewModel.sendInviteTransaction()
        viewModel.sendInviteStatusLiveData.observe(
            viewLifecycleOwner,
            Observer {
                if (it.status != Status.LOADING) {
                    dismissProgress()
                }
                when (it.status) {
                    Status.SUCCESS -> {
                        if (it.data != null) {
                            safeNavigate(
                                InviteFriendFragmentDirections
                                    .inviteFriendFragmentToInviteCreatedFragment(identityId = it.data.userId, startedFromHistory = startedByHistory),
                            )
                        }
                    }
                    Status.LOADING -> {
                        // sending has begun
                    }
                    else -> {
                        // there was an error sending
                        val errorDialog = FancyAlertDialog.newInstance(
                            R.string.invitation_creating_error_title,
                            R.string.invitation_creating_error_message,
                            R.drawable.ic_error_creating_invitation,
                            R.string.okay,
                            0,
                        )
                        errorDialog.show(childFragmentManager, null)
                        viewModel.logEvent(AnalyticsConstants.Invites.ERROR_CREATE)
                    }
                }
            },
        )
    }

    private fun showConfirmationDialog() {
        val upgradeFee = Constants.DASH_PAY_FEE
        val dialogTitle = getString(R.string.invitation_confirm_title)
        val dialogMessage = getString(R.string.invitation_confirm_message)
        val dialog = PlatformPaymentConfirmDialog.createDialog(dialogTitle, dialogMessage, upgradeFee)
        dialog.show(childFragmentManager, null)
    }

    private fun showProgress() {
        if (::loadingDialog.isInitialized && loadingDialog.isAdded) {
            loadingDialog.dismissAllowingStateLoss()
        }
        loadingDialog = FancyAlertDialog.newProgress(R.string.invitation_creating_progress_title, 0)
        loadingDialog.show(parentFragmentManager, null)
    }

    private fun dismissProgress() {
        if (loadingDialog.isAdded) {
            loadingDialog.dismissAllowingStateLoss()
        }
    }
}
