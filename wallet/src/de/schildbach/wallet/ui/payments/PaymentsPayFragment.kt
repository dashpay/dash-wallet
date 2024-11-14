/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.payments

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.payments.parsers.PaymentIntentParser
import de.schildbach.wallet.payments.parsers.PaymentIntentParserException
import de.schildbach.wallet.ui.dashpay.ContactsScreenMode
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.FrequentContactsAdapter
import de.schildbach.wallet.ui.dashpay.OnContactItemClickListener
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentPaymentsPayBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import javax.inject.Inject

@AndroidEntryPoint
class PaymentsPayFragment : Fragment(R.layout.fragment_payments_pay), OnContactItemClickListener {
    companion object {
        @JvmStatic
        fun newInstance() = PaymentsPayFragment()
    }

    private var frequentContactsAdapter: FrequentContactsAdapter = FrequentContactsAdapter()
    private val dashPayViewModel by viewModels<DashPayViewModel>()
    @Inject lateinit var analytics: AnalyticsService
    @Inject lateinit var blockchainIdentityDataDao: BlockchainIdentityConfig
    private val binding by viewBinding(FragmentPaymentsPayBinding::bind)

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intent = result.data

        if (result.resultCode == Activity.RESULT_OK && intent != null) {
            val input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            input?.let { handleString(input) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.contactsBtn.isVisible = false

        dashPayViewModel.hasContacts.observe(viewLifecycleOwner) {
            binding.contactsBtn.isVisible = it
        }

        binding.contactsBtn.setOnClickListener {
            handleSelectContact()
        }

        binding.scanBtn.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.SendReceive.SCAN_TO_SEND, mapOf())
            val intent = ScanActivity.getTransitionIntent(activity, binding.scanBtn)
            scanLauncher.launch(intent)
        }

        binding.sendBtn.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.SendReceive.SEND_TO_ADDRESS, mapOf())
            val destination = findNavController().currentDestination as? FragmentNavigator.Destination

            if (destination?.className == PaymentsFragment::class.java.name) {
                findNavController().navigate(PaymentsFragmentDirections.paymentsToAddressInput())
            }
        }

        binding.frequentContactsRv.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        binding.frequentContactsRv.adapter = this.frequentContactsAdapter
        this.frequentContactsAdapter.itemClickListener = this

        initViewModel()
        dashPayViewModel.getFrequentContacts()
    }

    private fun initViewModel() {
        dashPayViewModel.blockchainIdentity.observe(viewLifecycleOwner) {
            val visibility = if (it == null) View.GONE else View.VISIBLE
            binding.contactsPane.visibility = visibility
        }

        dashPayViewModel.frequentContactsLiveData.observe(viewLifecycleOwner) {
            if (Status.SUCCESS == it.status) {
                if (it.data == null || it.data.isEmpty()) {
                    binding.frequentContactsRv.visibility = View.GONE
                    // TODO: how do we show an arrow
                    // binding.payByContactSelect.showForwardArrow(false)
                } else {
                    binding.frequentContactsRv.visibility = View.VISIBLE
                    // TODO: how do we show an arrow
                    // binding.payByContactSelect.showForwardArrow(true)
                }
                binding.frequentContactsRv.visibility = binding.frequentContactsRv.visibility

                if (it.data != null) {
                    val results = arrayListOf<UsernameSearchResult>()
                    results.addAll(it.data)
                    frequentContactsAdapter.results = results
                }
            } else if (it.status == Status.ERROR) {
                binding.frequentContactsRv.visibility = View.GONE
            }
        }
    }

    private fun handleSelectContact() {
        dashPayViewModel.logEvent(AnalyticsConstants.UsersContacts.TAB_SEND_TO_CONTACT)
        findNavController().navigate(
            PaymentsFragmentDirections.paymentsToContacts(
                ShowNavBar = false,
                mode = ContactsScreenMode.SELECT_CONTACT
            )
        )
    }

    private fun handleString(input: String) {
        lifecycleScope.launch {
            try {
                val paymentIntent = PaymentIntentParser.parse(input, true)
                SendCoinsActivity.start(requireContext(), paymentIntent)
            } catch (ex: PaymentIntentParserException) {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.error),
                    ex.localizedMessage.format(resources),
                    getString(R.string.button_dismiss)
                ).show(requireActivity())
            }
        }
    }

    override fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult) {
        handleString(usernameSearchResult.fromContactRequest!!.userId)
    }
}
