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
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.dashpay.PlatformPaymentConfirmDialog
import de.schildbach.wallet.ui.dashpay.work.SendInviteOperation
import de.schildbach.wallet.ui.setupActionBarWithTitle
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_invite_friend.*
import org.bitcoinj.core.Coin

class InviteFriendFragment : Fragment(R.layout.fragment_invite_friend) {

    companion object {
        fun newInstance() = InviteFriendFragment()
    }

    private lateinit var walletApplication: WalletApplication

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
            triggerInviteCreation()
            requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.container, InvitationCreatedFragment.newInstance())
                    .commitNow()
        })
    }

    private fun showConfirmationDialog() {
        val upgradeFee = Coin.CENT
        val dialogTitle = getString(R.string.invitation_confirm_title)
        val dialogMessage = getString(R.string.invitation_confirm_message)
        val dialog = PlatformPaymentConfirmDialog.createDialog(dialogTitle, dialogMessage, upgradeFee.value)
        dialog.show(childFragmentManager, "SendInviteConfirmDialog")
    }

    private fun triggerInviteCreation() {
        SendInviteOperation(walletApplication)
                .create()
                .enqueue()
    }
}
