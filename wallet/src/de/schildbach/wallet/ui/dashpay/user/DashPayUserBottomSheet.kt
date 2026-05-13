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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
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
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Resource
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
import org.dash.wallet.common.ui.components.NavBarClose
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
        NavBarClose(onCloseClick = onCloseClick)

        if (userData != null) {
            UserInfoCard(
                profile = userData.dashPayProfile,
                userData = userData,
                state = state,
                onSendOrAcceptClick = onSendOrAcceptClick,
                onIgnoreClick = onIgnoreClick,
                onPayClick = onPayClick
            )
        }

        if (state.notifications.isNotEmpty()) {
            ActivitySection(
                notifications = state.notifications,
                onNotificationClick = onNotificationClick
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun UserInfoCard(
    profile: DashPayProfile,
    userData: UsernameSearchResult,
    state: DashPayUserBottomSheetUIState,
    onSendOrAcceptClick: () -> Unit,
    onIgnoreClick: () -> Unit,
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
            onIgnoreClick = onIgnoreClick,
            onPayClick = onPayClick,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ActivitySection(
    notifications: List<NotificationItem>,
    onNotificationClick: (NotificationItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.notifications_profile_activity),
            style = MyTheme.Typography.LabelLarge,
            color = MyTheme.Colors.textSecondary,
            modifier = Modifier.fillMaxWidth()
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MyTheme.Colors.backgroundSecondary)
                .padding(6.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                items(notifications, key = { it.getId() }) { item ->
                    NotificationRow(item = item, onClick = { onNotificationClick(item) })
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(item: NotificationItem, onClick: () -> Unit) {
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
                ProfileAvatar(
                    avatarUrl = profile.avatarUrl,
                    username = profile.username,
                    modifier = Modifier.size(30.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.displayName.ifEmpty { profile.username },
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
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(MyTheme.Colors.dashBlue5, shape = CircleShape)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.transaction_row_status_sent),
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
                val amount = item.tx?.let { tx ->
                    try {
                        tx.outputs.firstOrNull()?.value?.toFriendlyString() ?: ""
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

private fun previewContactRequest(userId: String, toUserId: String) = DashPayContactRequest(
    userId = userId,
    toUserId = toUserId,
    accountReference = 0,
    encryptedPublicKey = ByteArray(0),
    senderKeyIndex = 0,
    recipientKeyIndex = 0,
    timestamp = System.currentTimeMillis() - 60_000L,
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
