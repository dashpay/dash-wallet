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

import android.os.Bundle
import android.view.Gravity.CENTER_VERTICAL
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.compose_views.createImportPrivateKeyDialog
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentPaymentsReceiveBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.viewBinding
import javax.inject.Inject
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash
import android.widget.Toast

@AndroidEntryPoint
class PaymentsReceiveFragment : Fragment(R.layout.fragment_payments_receive) {
    private val binding by viewBinding(FragmentPaymentsReceiveBinding::bind)
    private val viewModel by viewModels<PaymentsViewModel>()

    companion object {
        private const val SHOW_IMPORT_PRIVATE_KEY_ARG = "showImportPrivateKey"
        private const val CENTER_VERTICALLY_KEY_ARG = "centerVertically"
        private const val FROM_QUICK_RECEIVE_KEY_ARG = "fromQuickReceive"

        /**
         * Whether a failed receive-address read should be shown to the user.
         *
         * Receive is a ViewPager2 page of [PaymentsFragment], so this fragment
         * — and the address request it starts — stays alive while the user is
         * on the Send or Internal tab, and in the two-tab layout Receive is
         * also built as the adjacent page while Send is selected. ViewPager2
         * caps an offscreen page at [Lifecycle.State.STARTED] and lifts only
         * the selected page to [Lifecycle.State.RESUMED], so RESUMED — not
         * STARTED — is what separates "the user is looking at Receive" from
         * "this failure belongs to a page the user cannot see". Offscreen the
         * failure is silent: the address area is simply left empty, which is
         * the correct post-cutover outcome either way, and reaching RESUMED is
         * also what asks [PaymentsViewModel.requestReceiveAddress] to try
         * again.
         */
        @VisibleForTesting
        @JvmStatic
        fun shouldSurfaceAddressFailure(viewState: Lifecycle.State): Boolean =
            viewState.isAtLeast(Lifecycle.State.RESUMED)

        @JvmStatic
        fun newInstance(): PaymentsReceiveFragment {
            return PaymentsReceiveFragment().apply {
                arguments = bundleOf(
                    SHOW_IMPORT_PRIVATE_KEY_ARG to true,
                    CENTER_VERTICALLY_KEY_ARG to false,
                    FROM_QUICK_RECEIVE_KEY_ARG to false
                )
            }
        }
    }

    private val args by navArgs<PaymentsReceiveFragmentArgs>()
    @Inject lateinit var walletDataProvider: WalletData

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.fromQuickReceive = args.fromQuickReceive
        viewModel.logEvent(AnalyticsConstants.SendReceive.SHOW_QR_CODE)

        binding.receiveInfo.setOnSpecifyAmountClicked {
            viewModel.logSpecifyAmount()
            findNavController().navigate(PaymentsFragmentDirections.paymentsToReceive())
        }
        binding.receiveInfo.setOnAddressClicked {
            viewModel.logEvent(AnalyticsConstants.SendReceive.COPY_ADDRESS)
        }
        binding.receiveInfo.setOnShareClicked {
            viewModel.logEvent(AnalyticsConstants.SendReceive.SHARE)
        }

        binding.importPrivateKeyBtn.isVisible = args.showImportPrivateKey
        binding.importPrivateKeyBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.SendReceive.IMPORT_PRIVATE_KEY)
            createImportPrivateKeyDialog(
                onScanPrivateKey = {
                    SweepWalletActivity.start(requireContext(), false)
                }
            ).show(parentFragmentManager, "import_private_key")
        }

        if (args.centerVertically) {
            binding.content.updateLayoutParams<FrameLayout.LayoutParams> {
                this.gravity = CENTER_VERTICAL
                topMargin = -100
            }
        }

        viewModel.dashPayProfile.observe(viewLifecycleOwner) {
            binding.receiveInfo.setProfile(it?.username, it?.displayName, it?.avatarUrl, it?.avatarHash)
        }

        // Collected for as long as the VIEW lives, not only while the page is
        // RESUMED: an offscreen page must still pick up an address that arrives
        // while the user is on another tab, and must still consume — silently —
        // a failure that lands there.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.receiveAddress.collect(::showReceiveAddress)
        }

        // Retry, and revalidate, on selection. The read above can fail offscreen
        // (the engine may simply not be bound yet), and ViewPager2 then RESUMES
        // the retained page rather than recreating its view, so without this the
        // page would stay blank forever even once the engine recovered. A resume
        // after a SUCCESSFUL read matters too: this ViewModel outlives the view,
        // so its address may have been paid — and the engine's next-unused
        // pointer moved past it — while the user was on another tab. The read is
        // idempotent while the address is unpaid, and single-flight is the
        // ViewModel's gate, so a resume during a slow read still costs nothing.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.requestReceiveAddress()
            }
        }

        // Prefetch, including while this page is the offscreen one built next to
        // Send, so the address is usually ready by the time Receive is selected.
        // On a RECREATED view this is also the revalidation of whatever address
        // the retained ViewModel is still holding.
        viewModel.requestReceiveAddress()
    }

    private fun showReceiveAddress(state: ReceiveAddressState) {
        // Every state says what, if anything, it may advertise. A revalidation
        // of an address already on screen reports it through
        // ReceiveAddressState.Loading, so the QR stays up for the five seconds
        // a cold engine read can take instead of blanking; a revalidation that
        // FAILS reports nothing, and the QR is taken down.
        val address = state.address

        if (address != null) {
            binding.receiveInfo.setInfo(address, null)
        } else {
            // Advertise nothing: ReceiveInfoView with no address set keeps the
            // address line and the QR code empty, which is the correct
            // post-cutover outcome — the held dashj chain's frozen address is
            // one the wallet has already been paid on (SR-03), and an address
            // whose revalidation failed can no longer be vouched for either.
            binding.receiveInfo.clearInfo()
        }

        // Handling stays inside Receive and never navigates: the failure can
        // land while the user is on another tab of the shared pager, and
        // popping here would resolve the parent NavController and close the
        // whole Payments destination out from under the tab in use.
        if (state is ReceiveAddressState.Unavailable &&
            shouldSurfaceAddressFailure(viewLifecycleOwner.lifecycle.currentState)
        ) {
            Toast.makeText(
                requireContext(),
                org.dash.wallet.common.R.string.loading_error,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
