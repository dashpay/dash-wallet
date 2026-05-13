/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay.user

import android.os.Bundle
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet.data.NotificationItemContact
import de.schildbach.wallet.data.NotificationItemPayment
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.ui.compose_views.ProfileAvatar
import de.schildbach.wallet.ui.dashpay.widget.ContactRequestPaneCompose
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.ui.transactions.TransactionDetailsDialogFragment
import de.schildbach.wallet.ui.util.InputParser
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.ComposeBottomSheet

@AndroidEntryPoint
class DashPayUserBottomSheet : ComposeBottomSheet() {

    companion object {
        const val REQUEST_KEY = "DashPayUserBottomSheet_request"
        const val KEY_CHANGED = "changed"

        private const val ARG_USERNAME_SEARCH_RESULT = "arg_username_search_result"
        private const val ARG_DASHPAY_PROFILE = "arg_dashpay_profile"
        private const val ARG_SHOW_CONTACT_HISTORY_DISCLAIMER = "arg_show_contact_history_disclaimer"

        fun newInstance(
            usernameSearchResult: UsernameSearchResult,
            showContactHistoryDisclaimer: Boolean = false
        ): DashPayUserBottomSheet {
            return DashPayUserBottomSheet().apply {
                arguments = bundleOf(
                    ARG_USERNAME_SEARCH_RESULT to usernameSearchResult,
                    ARG_SHOW_CONTACT_HISTORY_DISCLAIMER to showContactHistoryDisclaimer
                )
            }
        }

        fun newInstance(dashPayProfile: DashPayProfile): DashPayUserBottomSheet {
            return DashPayUserBottomSheet().apply {
                arguments = bundleOf(
                    ARG_DASHPAY_PROFILE to dashPayProfile,
                    ARG_SHOW_CONTACT_HISTORY_DISCLAIMER to false
                )
            }
        }
    }

    private fun resolveInitialUserData(args: Bundle?): UsernameSearchResult? {
        if (args == null) return null
        @Suppress("DEPRECATION")
        args.getParcelable<UsernameSearchResult>(ARG_USERNAME_SEARCH_RESULT)?.let { return it }
        @Suppress("DEPRECATION")
        args.getParcelable<DashPayProfile>(ARG_DASHPAY_PROFILE)?.let { profile ->
            return UsernameSearchResult(profile.username, profile, null, null)
        }
        return null
    }

    @Composable
    override fun Content() {
        val viewModel: DashPayUserBottomSheetViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val initialUserData = remember { resolveInitialUserData(arguments) }

        LaunchedEffect(Unit) {
            initialUserData?.let { viewModel.initUserData(it) }
        }

        DashPayUserContent(
            state = state,
            onCloseClick = { dismiss() },
            onSendOrAcceptClick = {
                notifyContactChange()
                lifecycleScope.launch {
                    handleCreditCheckAndSend(viewModel)
                }
            },
            onIgnoreClick = { /* not yet implemented, mirror activity */ },
            onPayClick = {
                state.userData?.let { startPayActivity(it) }
            },
            onNotificationClick = { item ->
                if (item is NotificationItemPayment && item.tx != null) {
                    TransactionDetailsDialogFragment.newInstance(item.tx.txId)
                        .show(parentFragmentManager, null)
                }
            }
        )
    }

    private fun notifyContactChange() {
        setFragmentResult(REQUEST_KEY, bundleOf(KEY_CHANGED to true))
    }

    private suspend fun handleCreditCheckAndSend(viewModel: DashPayUserBottomSheetViewModel) {
        val activity = requireActivity()
        val outcome = viewModel.checkCreditsAndSend()
        when (outcome) {
            DashPayUserBottomSheetViewModel.CreditCheckOutcome.ShowError -> {
                AdaptiveDialog.create(
                    R.drawable.ic_warning_yellow_circle,
                    getString(R.string.platform_credits_error),
                    getString(R.string.platform_communication_error),
                    getString(R.string.button_ok)
                ).showAsync(activity)
                viewModel.resetCreditCheck()
            }
            DashPayUserBottomSheetViewModel.CreditCheckOutcome.ShowWarningEmpty,
            DashPayUserBottomSheetViewModel.CreditCheckOutcome.ShowWarningLow -> {
                val isEmpty = outcome == DashPayUserBottomSheetViewModel.CreditCheckOutcome.ShowWarningEmpty
                val answer = AdaptiveDialog.create(
                    R.drawable.ic_warning_yellow_circle,
                    if (isEmpty) getString(R.string.credit_balance_empty_warning_title)
                    else getString(R.string.credit_balance_low_warning_title),
                    if (isEmpty) getString(R.string.credit_balance_empty_warning_message)
                    else getString(R.string.credit_balance_low_warning_message),
                    getString(R.string.credit_balance_button_maybe_later),
                    getString(R.string.credit_balance_button_buy)
                ).showAsync(activity)
                if (answer == true) {
                    SendCoinsActivity.startBuyCredits(activity)
                } else if (!isEmpty) {
                    viewModel.sendContactRequest()
                }
                viewModel.resetCreditCheck()
            }
            DashPayUserBottomSheetViewModel.CreditCheckOutcome.Proceed -> {
                viewModel.sendContactRequest()
                viewModel.resetCreditCheck()
            }
        }
    }

    private fun startPayActivity(userData: UsernameSearchResult) {
        val activity = requireActivity()
        object : InputParser.StringInputParser(userData.dashPayProfile.userId, true) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                SendCoinsActivity.start(activity, null, paymentIntent, true)
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                val message = if (messageArgs.isNotEmpty()) {
                    getString(messageResId, messageArgs)
                } else {
                    getString(messageResId)
                }
                val dialog = AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.scan_to_pay_username_dialog_message),
                    message,
                    getString(R.string.button_close),
                    null
                )
                dialog.isMessageSelectable = true
                dialog.show(activity)
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                // ignore
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {
                // ignore
            }
        }.parse()
        dismiss()
    }
}

@Composable
private fun DashPayUserContent(
    state: DashPayUserBottomSheetUIState,
    onCloseClick: () -> Unit,
    onSendOrAcceptClick: () -> Unit,
    onIgnoreClick: () -> Unit,
    onPayClick: () -> Unit,
    onNotificationClick: (NotificationItem) -> Unit
) {
    val userData = state.userData
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onCloseClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_blue),
                    contentDescription = stringResource(R.string.button_close),
                    tint = MyTheme.Colors.dashBlue
                )
            }
        }

        if (userData != null) {
            ProfileHeader(userData.dashPayProfile)
            Spacer(modifier = Modifier.height(8.dp))
            ContactRequestPaneSection(
                state = state,
                onSendOrAcceptClick = onSendOrAcceptClick,
                onIgnoreClick = onIgnoreClick,
                onPayClick = onPayClick
            )
        }

        if (state.notifications.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 25.dp, vertical = 8.dp),
                color = MyTheme.Colors.lightGray
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .padding(horizontal = 15.dp, vertical = 8.dp)
            ) {
                items(state.notifications, key = { it.getId() }) { item ->
                    NotificationRow(item = item, onClick = { onNotificationClick(item) })
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(profile: DashPayProfile) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ProfileAvatar(
            avatarUrl = profile.avatarUrl,
            username = profile.username,
            modifier = Modifier.size(128.dp)
        )
        val displayName = profile.displayName
        if (displayName.isNotEmpty()) {
            Spacer(modifier = Modifier.height(17.dp))
            Text(
                text = displayName,
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = profile.username,
                style = MyTheme.Typography.LabelMedium,
                color = MyTheme.Colors.textSecondary
            )
        } else {
            Spacer(modifier = Modifier.height(17.dp))
            Text(
                text = profile.username,
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textPrimary
            )
        }
    }
}

@Composable
private fun ContactRequestPaneSection(
    state: DashPayUserBottomSheetUIState,
    onSendOrAcceptClick: () -> Unit,
    onIgnoreClick: () -> Unit,
    onPayClick: () -> Unit
) {
    val userData = state.userData ?: return
    ContactRequestPaneCompose(
        userData = userData,
        sendContactRequestState = state.sendContactRequestState,
        isNetworkError = state.networkError,
        onSendOrAcceptClick = onSendOrAcceptClick,
        onIgnoreClick = onIgnoreClick,
        onPayClick = onPayClick,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NotificationRow(item: NotificationItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (item) {
            is NotificationItemContact -> {
                val profile = item.usernameSearchResult.dashPayProfile
                ProfileAvatar(
                    avatarUrl = profile.avatarUrl,
                    username = profile.username,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.displayName.ifEmpty { profile.username },
                        style = MyTheme.Typography.BodyMediumMedium,
                        color = MyTheme.Colors.textPrimary
                    )
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            item.getDate(),
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString(),
                        style = MyTheme.Typography.LabelMedium,
                        color = MyTheme.Colors.textSecondary
                    )
                }
            }
            is NotificationItemPayment -> {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MyTheme.Colors.dashBlue5, shape = RoundedCornerShape(20.dp))
                )
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.transaction_row_status_sent),
                        style = MyTheme.Typography.BodyMediumMedium,
                        color = MyTheme.Colors.textPrimary
                    )
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            item.getDate() / 1000,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString(),
                        style = MyTheme.Typography.LabelMedium,
                        color = MyTheme.Colors.textSecondary
                    )
                }
                val amount = item.tx?.let { tx ->
                    try {
                        tx.outputs.firstOrNull()?.value?.toFriendlyString() ?: ""
                    } catch (_: Exception) { "" }
                } ?: ""
                if (amount.isNotEmpty()) {
                    Text(
                        text = amount,
                        style = MyTheme.Typography.BodyMediumMedium,
                        color = MyTheme.Colors.textPrimary
                    )
                }
            }
            else -> {
                Text(
                    text = item.getId(),
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.textSecondary
                )
            }
        }
    }
}
