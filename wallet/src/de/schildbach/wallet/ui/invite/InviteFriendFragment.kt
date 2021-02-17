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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.InvitationFragmentViewModel
import de.schildbach.wallet.ui.dashpay.PlatformPaymentConfirmDialog
import de.schildbach.wallet.ui.setupActionBarWithTitle
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_invite_friend.*
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.ui.FancyAlertDialog

class InviteFriendFragment : Fragment(R.layout.fragment_invite_friend) {

    companion object {
        fun newInstance() = InviteFriendFragment()
    }

    private lateinit var walletApplication: WalletApplication

    val viewModel by lazy {
        ViewModelProvider(requireActivity()).get(InvitationFragmentViewModel::class.java)
    }

    private lateinit var loadingDialog: FancyAlertDialog

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupActionBarWithTitle(R.string.invitation_init_title)

        walletApplication = requireActivity().application as WalletApplication
        create_invitation_button.setOnClickListener {
            showConfirmationDialog()
        }

        initViewModel()
    }

    private fun initViewModel() {
        val platformPaymentConfirmDialogViewModel = ViewModelProvider(requireActivity())[PlatformPaymentConfirmDialog.SharedViewModel::class.java]
        platformPaymentConfirmDialogViewModel.clickConfirmButtonEvent.observe(viewLifecycleOwner, Observer {
            confirmButtonClick()
        })
    }

    private fun confirmButtonClick() {
        showProgress()
        // is there enough funds?
        if (walletApplication.wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE) < Constants.DASH_PAY_FEE) {
            val errorDialog = FancyAlertDialog.newInstance(R.string.invitation_cant_afford_title,
                    R.string.invitation_cant_afford_message, R.drawable.ic_cant_afford_invitation,
                    0, R.string.invitation_preview_close)
            errorDialog.show(requireActivity().supportFragmentManager, null)
        } else {
            // there are enough funds
            val inviteId = viewModel.sendInviteTransaction()
            viewModel.sendInviteStatusLiveData.observe(viewLifecycleOwner, {
                if (it.status != Status.LOADING) {
                    dismissProgress()
                }
                when (it.status) {
                    Status.SUCCESS -> {
                        if (it.data != null) {
                            requireActivity().supportFragmentManager.beginTransaction()
                                    .replace(R.id.container, InvitationCreatedFragment.newInstanceFromInvitation())
                                    .commitNow()
                        }
                    }
                    Status.LOADING -> {
                        // sending has begun
                    }
                    else -> {
                        // there was an error sending
                        val errorDialog = FancyAlertDialog.newInstance(R.string.invitation_creating_error_title,
                                R.string.invitation_creating_error_message, R.drawable.ic_error_creating_invitation,
                                R.string.okay, 0)
                        errorDialog.show(childFragmentManager, null)
                    }
                }
            })
        }

    }

    private fun showConfirmationDialog() {
        val upgradeFee = Coin.CENT
        val dialogTitle = getString(R.string.invitation_confirm_title)
        val dialogMessage = getString(R.string.invitation_confirm_message)
        val dialog = PlatformPaymentConfirmDialog.createDialog(dialogTitle, dialogMessage, upgradeFee.value)
        dialog.show(childFragmentManager, null)
    }

    private fun showProgress() {
        if (::loadingDialog.isInitialized && loadingDialog.isAdded) {
            loadingDialog.dismissAllowingStateLoss()
        }
        loadingDialog = FancyAlertDialog.newProgress(R.string.invitation_creating_progress_message, 0)
        loadingDialog.show(parentFragmentManager, null)
    }

    private fun dismissProgress() {
        if (loadingDialog.isAdded) {
            loadingDialog.dismissAllowingStateLoss()
        }
    }
}
