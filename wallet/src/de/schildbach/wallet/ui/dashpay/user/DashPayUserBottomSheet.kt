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
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.NotificationItem
import de.schildbach.wallet.data.NotificationItemContact
import de.schildbach.wallet.data.NotificationItemPayment
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
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
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarClose
import org.dash.wallet.common.ui.components.Style
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

    override val backgroundStyle: Int = R.style.PrimaryBackground

    // Auto-expand is a one-shot decision; once we've expanded for content fit, leave the
    // sheet alone so a user-initiated drag isn't overridden by later state updates. Backed
    // by Compose state so the content layout can switch to a height-filling, scrollable
    // activity list when expanded.
    private var hasAutoExpanded by mutableStateOf(false)

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

        LaunchedEffect(state.userData?.type, state.notifications.size) {
            applyAutoExpandIfNeeded(state.userData?.type, state.notifications.size)
        }

        DashPayUserContent(
            state = state,
            isFullScreen = hasAutoExpanded,
            onCloseClick = { dismiss() },
            onSendOrAcceptClick = {
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
            },
            onFilterSelected = viewModel::setFilter,
            isSentTransaction = viewModel::isSentTransaction
        )
    }

    private fun notifyContactChange() {
        setFragmentResult(REQUEST_KEY, bundleOf(KEY_CHANGED to true))
    }

    private fun applyAutoExpandIfNeeded(
        type: UsernameSearchResult.Type?,
        notificationCount: Int
    ) {
        if (hasAutoExpanded) return
        val shouldExpand = when (type) {
            UsernameSearchResult.Type.CONTACT_ESTABLISHED -> notificationCount > 3
            UsernameSearchResult.Type.REQUEST_RECEIVED -> notificationCount > 2
            else -> false
        }
        if (!shouldExpand) return

        val sheet = (dialog as? BottomSheetDialog)
            ?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        val marginTop = resources.getDimensionPixelSize(R.dimen.offset_dialog_margin_top)

        sheet.layoutParams = (sheet.layoutParams as CoordinatorLayout.LayoutParams).apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        BottomSheetBehavior.from(sheet).apply {
            expandedOffset = marginTop
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        (sheet.parent as? CoordinatorLayout)?.parent?.requestLayout()
        hasAutoExpanded = true
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
                    notifyContactChange()
                }
                viewModel.resetCreditCheck()
            }
            DashPayUserBottomSheetViewModel.CreditCheckOutcome.Proceed -> {
                viewModel.sendContactRequest()
                notifyContactChange()
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
    isFullScreen: Boolean,
    onCloseClick: () -> Unit,
    onSendOrAcceptClick: () -> Unit,
    onIgnoreClick: () -> Unit,
    onPayClick: () -> Unit,
    onNotificationClick: (NotificationItem) -> Unit,
    onFilterSelected: (NotificationFilter) -> Unit = {},
    isSentTransaction: (Transaction) -> Boolean = { false }
) {
    val userData = state.userData
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Only fill height when the sheet has been auto-expanded to MATCH_PARENT;
            // otherwise the wrap_content sheet would balloon to full height for every contact.
            .then(if (isFullScreen) Modifier.fillMaxHeight() else Modifier)
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        NavBarClose(onCloseClick = onCloseClick)

        if (userData != null) {
            UserInfoCard(
                profile = userData.dashPayProfile,
                userData = userData,
                state = state,
                onSendOrAcceptClick = onSendOrAcceptClick,
                onPayClick = onPayClick
            )

            if (userData.type == UsernameSearchResult.Type.REQUEST_RECEIVED) {
                RequestReceivedCard(
                    username = userData.dashPayProfile.displayName.ifEmpty { userData.dashPayProfile.username },
                    isLoading = state.sendContactRequestState?.status == Status.LOADING,
                    isNetworkError = state.networkError,
                    onIgnoreClick = onIgnoreClick,
                    onAcceptClick = onSendOrAcceptClick
                )
            }
        }

        // Show the Activity section whenever we have notifications OR a non-default filter is
        // active — otherwise selecting "Sent" with no sent items would hide the section and
        // strand the dropdown.
        if (state.notifications.isNotEmpty() || state.filter != NotificationFilter.ALL) {
            ActivitySection(
                notifications = state.notifications,
                activeFilter = state.filter,
                isFullScreen = isFullScreen,
                onFilterSelected = onFilterSelected,
                onNotificationClick = onNotificationClick,
                isSentTransaction = isSentTransaction
            )
        }

        if (!isFullScreen) {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun UserInfoCard(
    profile: DashPayProfile,
    userData: UsernameSearchResult,
    state: DashPayUserBottomSheetUIState,
    onSendOrAcceptClick: () -> Unit,
    onPayClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(
                    avatarUrl = profile.avatarUrl,
                    username = profile.username,
                    modifier = Modifier.size(60.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.username,
                        style = MyTheme.Typography.TitleMediumMedium,
                        color = MyTheme.Colors.textPrimary
                    )
                    if (profile.displayName.isNotEmpty()) {
                        Text(
                            text = profile.displayName,
                            style = MyTheme.Typography.LabelMedium,
                            color = MyTheme.Colors.textTertiary
                        )
                    }
                }
            }
            if (profile.publicMessage.isNotEmpty()) {
                Text(
                    text = profile.publicMessage,
                    style = MyTheme.Typography.TitleSmall,
                    color = MyTheme.Colors.textPrimary
                )
            }
        }

        ContactRequestPaneCompose(
            userData = userData,
            sendContactRequestState = state.sendContactRequestState,
            isNetworkError = state.networkError,
            onSendOrAcceptClick = onSendOrAcceptClick,
            onPayClick = onPayClick,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RequestReceivedCard(
    username: String,
    isLoading: Boolean,
    isNetworkError: Boolean,
    onIgnoreClick: () -> Unit,
    onAcceptClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.contact_request_received_card_title, username),
                style = MyTheme.Typography.TitleMediumMedium,
                color = MyTheme.Colors.textPrimary
            )
            Text(
                text = stringResource(R.string.contact_request_received_card_message, username),
                style = MyTheme.Typography.TitleSmall,
                color = MyTheme.Colors.textSecondary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            DashButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.contact_request_ignore),
                style = Style.TintedGray,
                isEnabled = !isLoading,
                onClick = onIgnoreClick
            )
            DashButton(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.contact_request_accept),
                style = Style.FilledGreen,
                isEnabled = !isNetworkError,
                isLoading = isLoading,
                onClick = onAcceptClick
            )
        }
    }
}

// CONTACT_ESTABLISHED emits two contact rows for the same user (the established record + an
// "invitationOfEstablished" marker). Both have the same `getId()`, which crashes LazyColumn.
// Compose the flag in so the keys stay unique without touching the shared `getId()` contract
// used by NotificationsAdapter.
private fun NotificationItem.lazyKey(): String = when (this) {
    is NotificationItemContact -> "contact:${getId()}:${isInvitationOfEstablished}"
    else -> getId()
}

@Composable
private fun ColumnScope.ActivitySection(
    notifications: List<NotificationItem>,
    activeFilter: NotificationFilter,
    isFullScreen: Boolean,
    onFilterSelected: (NotificationFilter) -> Unit,
    onNotificationClick: (NotificationItem) -> Unit,
    isSentTransaction: (Transaction) -> Boolean
) {
    // In full-screen mode, the section claims all remaining vertical space so the inner
    // list can scroll inside it. In wrap_content mode, the section measures to its content
    // and the LazyColumn is capped so it stays scrollable rather than ballooning the sheet.
    val sectionModifier = if (isFullScreen) {
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = true)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    }
    Column(
        modifier = sectionModifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.notifications_profile_activity),
                style = MyTheme.Typography.LabelLarge,
                color = MyTheme.Colors.textSecondary
            )
            FilterButton(
                activeFilter = activeFilter,
                onFilterSelected = onFilterSelected
            )
        }
        val containerModifier = if (isFullScreen) {
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(MyTheme.Colors.backgroundSecondary)
                .padding(6.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MyTheme.Colors.backgroundSecondary)
                .padding(6.dp)
        }
        Column(modifier = containerModifier) {
            val listModifier = if (isFullScreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            }
            LazyColumn(modifier = listModifier) {
                items(notifications, key = { it.lazyKey() }) { item ->
                    NotificationRow(
                        item = item,
                        isSentTransaction = isSentTransaction,
                        onClick = { onNotificationClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterButton(
    activeFilter: NotificationFilter,
    onFilterSelected: (NotificationFilter) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_filter_icon),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MyTheme.Colors.textPrimary
            )
            Text(
                text = stringResource(R.string.activity_buy_and_sell_dash_filter),
                style = MyTheme.CaptionMedium,
                color = MyTheme.Colors.textPrimary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MyTheme.Colors.backgroundSecondary)
        ) {
            FilterMenuItem(R.string.all_transactions, NotificationFilter.ALL, activeFilter) {
                onFilterSelected(it); expanded = false
            }
            FilterMenuItem(R.string.received_transactions, NotificationFilter.RECEIVED, activeFilter) {
                onFilterSelected(it); expanded = false
            }
            FilterMenuItem(R.string.sent_transactions, NotificationFilter.SENT, activeFilter) {
                onFilterSelected(it); expanded = false
            }
        }
    }
}

@Composable
private fun FilterMenuItem(
    labelRes: Int,
    value: NotificationFilter,
    active: NotificationFilter,
    onClick: (NotificationFilter) -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(labelRes),
                style = MyTheme.Typography.BodyMedium,
                color = if (value == active) MyTheme.Colors.dashBlue else MyTheme.Colors.textPrimary
            )
        },
        trailingIcon = if (value == active) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark_blue),
                    contentDescription = null,
                    tint = MyTheme.Colors.dashBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else null,
        onClick = { onClick(value) }
    )
}

@Composable
private fun NotificationRow(
    item: NotificationItem,
    isSentTransaction: (Transaction) -> Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (item) {
            is NotificationItemContact -> {
                val profile = item.usernameSearchResult.dashPayProfile
                val type = item.usernameSearchResult.type
                // Sent (blue) when we initiated the event; received (green) when the counterparty did.
                // For CONTACT_ESTABLISHED, the newer request reflects who acted last: if our
                // acceptance is newer we sent it (blue); if their acceptance is newer we received it
                // (green). The paired invitation-marker row carries the opposite direction, so an
                // established contact always shows one of each.
                val iconRes = when (type) {
                    UsernameSearchResult.Type.REQUEST_SENT -> R.drawable.ic_notification_contact_sent
                    UsernameSearchResult.Type.REQUEST_RECEIVED -> R.drawable.ic_notification_contact_received
                    UsernameSearchResult.Type.CONTACT_ESTABLISHED -> {
                        val result = item.usernameSearchResult
                        val weAccepted = (result.toContactRequest?.timestamp ?: 0L) >
                                         (result.fromContactRequest?.timestamp ?: 0L)
                        if (weAccepted) R.drawable.ic_notification_contact_sent
                        else R.drawable.ic_notification_contact_received
                    }
                    else -> R.drawable.ic_notification_contact_received
                }
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(30.dp)
                )
                val name = profile.displayName.ifEmpty { profile.username }
                val title = when (type) {
                    UsernameSearchResult.Type.REQUEST_RECEIVED ->
                        stringResource(R.string.contact_request_row_received, name)
                    UsernameSearchResult.Type.REQUEST_SENT ->
                        stringResource(R.string.contact_request_row_sent)
                    UsernameSearchResult.Type.CONTACT_ESTABLISHED -> {
                        // Direction follows toNotificationItems(): toContactRequest newer than
                        // fromContactRequest means we accepted their earlier request.
                        val result = item.usernameSearchResult
                        val incoming = (result.toContactRequest?.timestamp ?: 0L) >
                                       (result.fromContactRequest?.timestamp ?: 0L)
                        if (incoming) {
                            stringResource(R.string.contact_request_row_established_incoming, name)
                        } else {
                            stringResource(R.string.contact_request_row_established_outgoing, name)
                        }
                    }
                    else -> name
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MyTheme.Typography.TitleSmallMedium,
                        color = MyTheme.Colors.textPrimary
                    )
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            item.getDate(),
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString(),
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.textSecondary
                    )
                }
            }
            is NotificationItemPayment -> {
                val tx = item.tx
                val sent = tx?.let(isSentTransaction) ?: false
                val iconRes = if (sent) R.drawable.ic_transaction_sent else R.drawable.ic_transaction_received
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.size(30.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (sent) R.string.transaction_row_status_sent
                            else R.string.transaction_row_status_received
                        ),
                        style = MyTheme.Typography.TitleSmallMedium,
                        color = MyTheme.Colors.textPrimary
                    )
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            item.getDate() / 1000,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString(),
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.textSecondary
                    )
                }
                val amount = tx?.let {
                    try {
                        it.outputs.firstOrNull()?.value?.toFriendlyString() ?: ""
                    } catch (_: Exception) { "" }
                } ?: ""
                if (amount.isNotEmpty()) {
                    Text(
                        text = amount,
                        style = MyTheme.Typography.TitleSmallMedium,
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

// ── Previews ───────────────────────────────────────────────────────────────

private fun previewProfile() = DashPayProfile(
    userId = "preview-user-id",
    username = "johndoe",
    displayName = "John Doe",
    publicMessage = "I am a multi-disciplinary maker of useful, curious and beautiful things.",
    avatarUrl = "",
    avatarHash = null,
    avatarFingerprint = null,
    createdAt = 0L,
    updatedAt = 0L
)

private fun previewContactRequest(
    userId: String,
    toUserId: String,
    timestamp: Long = System.currentTimeMillis() - 60_000L
) = DashPayContactRequest(
    userId = userId,
    toUserId = toUserId,
    accountReference = 0,
    encryptedPublicKey = ByteArray(0),
    senderKeyIndex = 0,
    recipientKeyIndex = 0,
    timestamp = timestamp,
    encryptedAccountLabel = null,
    autoAcceptProof = null
)

private fun previewUserData(type: UsernameSearchResult.Type): UsernameSearchResult {
    val profile = previewProfile()
    val me = "preview-self-id"
    val them = profile.userId
    return when (type) {
        UsernameSearchResult.Type.NO_RELATIONSHIP ->
            UsernameSearchResult(profile.username, profile, null, null)
        UsernameSearchResult.Type.REQUEST_SENT ->
            UsernameSearchResult(profile.username, profile, previewContactRequest(me, them), null)
        UsernameSearchResult.Type.REQUEST_RECEIVED ->
            UsernameSearchResult(profile.username, profile, null, previewContactRequest(them, me))
        UsernameSearchResult.Type.CONTACT_ESTABLISHED ->
            UsernameSearchResult(
                profile.username,
                profile,
                previewContactRequest(me, them),
                previewContactRequest(them, me)
            )
    }
}

private fun previewNotifications(profile: DashPayProfile): List<NotificationItem> {
    val result = UsernameSearchResult(
        profile.username,
        profile,
        previewContactRequest("preview-self-id", profile.userId),
        null
    )
    return listOf(NotificationItemContact(result))
}

@Composable
private fun DashPayUserPreviewFrame(state: DashPayUserBottomSheetUIState) {
    DashPayUserContent(
        state = state,
        isFullScreen = false,
        onCloseClick = {},
        onSendOrAcceptClick = {},
        onIgnoreClick = {},
        onPayClick = {},
        onNotificationClick = {}
    )
}

@Preview(name = "NONE — no relationship", showBackground = true, widthDp = 428, heightDp = 700)
@Composable
private fun PreviewNone() {
    DashPayUserPreviewFrame(
        state = DashPayUserBottomSheetUIState(
            userData = previewUserData(UsernameSearchResult.Type.NO_RELATIONSHIP)
        )
    )
}

@Preview(name = "INVITING — send pending", showBackground = true, widthDp = 428, heightDp = 700)
@Composable
private fun PreviewInviting() {
    DashPayUserPreviewFrame(
        state = DashPayUserBottomSheetUIState(
            userData = previewUserData(UsernameSearchResult.Type.NO_RELATIONSHIP),
            sendContactRequestState = Resource.loading()
        )
    )
}

@Preview(name = "INVITED — request sent", showBackground = true, widthDp = 428, heightDp = 800)
@Composable
private fun PreviewInvited() {
    val profile = previewProfile()
    DashPayUserPreviewFrame(
        state = DashPayUserBottomSheetUIState(
            userData = previewUserData(UsernameSearchResult.Type.REQUEST_SENT),
            notifications = previewNotifications(profile)
        )
    )
}

@Preview(name = "INVITE_RECEIVED", showBackground = true, widthDp = 428, heightDp = 700)
@Composable
private fun PreviewInviteReceived() {
    DashPayUserPreviewFrame(
        state = DashPayUserBottomSheetUIState(
            userData = previewUserData(UsernameSearchResult.Type.REQUEST_RECEIVED)
        )
    )
}

@Preview(name = "ACCEPTING_INVITE", showBackground = true, widthDp = 428, heightDp = 700)
@Composable
private fun PreviewAcceptingInvite() {
    DashPayUserPreviewFrame(
        state = DashPayUserBottomSheetUIState(
            userData = previewUserData(UsernameSearchResult.Type.REQUEST_RECEIVED),
            sendContactRequestState = Resource.loading()
        )
    )
}

@Preview(name = "FRIENDS — contact established", showBackground = true, widthDp = 428, heightDp = 800)
@Composable
private fun PreviewFriends() {
    val profile = previewProfile()
    DashPayUserPreviewFrame(
        state = DashPayUserBottomSheetUIState(
            userData = previewUserData(UsernameSearchResult.Type.CONTACT_ESTABLISHED),
            notifications = previewNotifications(profile)
        )
    )
}

/**
 * Builds the realistic `toNotificationItems` output for CONTACT_ESTABLISHED:
 * the established row plus an `isInvitationOfEstablished = true` marker whose
 * direction (REQUEST_RECEIVED vs REQUEST_SENT) depends on who sent the request first.
 */
private fun previewEstablishedNotifications(
    profile: DashPayProfile,
    theirRequestFirst: Boolean
): List<NotificationItem> {
    val me = "preview-self-id"
    val them = profile.userId
    val now = System.currentTimeMillis()
    val firstTs = now - 5 * 60_000L
    val secondTs = now - 60_000L

    val (toReq, fromReq) = if (theirRequestFirst) {
        // they → me first (fromContactRequest, earlier), then me → them acceptance (toContactRequest, later)
        previewContactRequest(me, them, secondTs) to previewContactRequest(them, me, firstTs)
    } else {
        // me → them first (toContactRequest, earlier), then them → me acceptance (fromContactRequest, later)
        previewContactRequest(me, them, firstTs) to previewContactRequest(them, me, secondTs)
    }

    val established = UsernameSearchResult(profile.username, profile, toReq, fromReq)
    val invitation = if (theirRequestFirst) {
        established.copy(toContactRequest = null) // → REQUEST_RECEIVED
    } else {
        established.copy(fromContactRequest = null) // → REQUEST_SENT
    }
    return listOf(
        NotificationItemContact(established),
        NotificationItemContact(invitation, isInvitationOfEstablished = true)
    )
}

@Preview(
    name = "FRIENDS — they sent request first, I accepted",
    showBackground = true,
    widthDp = 428,
    heightDp = 800
)
@Composable
private fun PreviewFriendsTheySentFirst() {
    val profile = previewProfile()
    val notifications = previewEstablishedNotifications(profile, theirRequestFirst = true)
    DashPayUserPreviewFrame(
        state = DashPayUserBottomSheetUIState(
            userData = (notifications.first() as NotificationItemContact).usernameSearchResult,
            notifications = notifications
        )
    )
}

@Preview(
    name = "FRIENDS — I sent request first, they accepted",
    showBackground = true,
    widthDp = 428,
    heightDp = 800
)
@Composable
private fun PreviewFriendsISentFirst() {
    val profile = previewProfile()
    val notifications = previewEstablishedNotifications(profile, theirRequestFirst = false)
    DashPayUserPreviewFrame(
        state = DashPayUserBottomSheetUIState(
            userData = (notifications.first() as NotificationItemContact).usernameSearchResult,
            notifications = notifications
        )
    )
}
