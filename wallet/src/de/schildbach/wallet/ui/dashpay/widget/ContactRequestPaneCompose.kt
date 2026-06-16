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
package de.schildbach.wallet.ui.dashpay.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.ContactRelation
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Style

/**
 * The action area (button + optional sub-disclaimer) of the DashPay user bottom sheet.
 * Lives inside the parent's white user-info card; doesn't draw its own background.
 */
@Composable
fun ContactRequestPaneCompose(
    userData: UsernameSearchResult,
    sendContactRequestState: Resource<*>?,
    isNetworkError: Boolean,
    onSendOrAcceptClick: () -> Unit,
    onPayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val relationship = resolveRelationship(userData.type, sendContactRequestState)
    val username = userData.username

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        when (relationship) {
            ContactRelation.Relationship.NONE ->
                DashButton(
                    text = stringResource(R.string.send_contact_request_short),
                    style = Style.FilledBlue,
                    isEnabled = !isNetworkError,
                    onClick = onSendOrAcceptClick
                )

            ContactRelation.Relationship.INVITING ->
                DashButton(
                    text = null,
                    style = Style.FilledBlue,
                    isLoading = true,
                    onClick = {}
                )

            ContactRelation.Relationship.INVITED ->
                DashButton(
                    text = stringResource(R.string.contact_request_sent_short),
                    style = Style.FilledBlue,
                    isEnabled = false,
                    onClick = {}
                )

            ContactRelation.Relationship.INVITE_RECEIVED,
            ContactRelation.Relationship.ACCEPTING_INVITE,
            ContactRelation.Relationship.FRIENDS ->
                DashButton(
                    text = stringResource(R.string.send_button_label),
                    leadingIcon = MyImages.DashDWhite,
                    style = Style.FilledBlue,
                    onClick = onPayClick
                )
        }

        if (relationship.showsPendingDisclaimer()) {
            DisclaimerText(
                text = stringResource(R.string.contact_history_disclaimer_pending_plain, username)
            )
        }
    }
}

private fun ContactRelation.Relationship.showsPendingDisclaimer(): Boolean = when (this) {
    ContactRelation.Relationship.NONE,
    ContactRelation.Relationship.INVITING,
    ContactRelation.Relationship.INVITED -> true
    else -> false
}

@Composable
private fun DisclaimerText(text: String) {
    Text(
        text = text,
        style = MyTheme.Typography.LabelMedium,
        color = MyTheme.Colors.textSecondary,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun resolveRelationship(
    type: UsernameSearchResult.Type,
    state: Resource<*>?
): ContactRelation.Relationship {
    return when (type) {
        UsernameSearchResult.Type.NO_RELATIONSHIP -> when (state?.status) {
            null -> ContactRelation.Relationship.NONE
            Status.LOADING -> ContactRelation.Relationship.INVITING
            Status.SUCCESS -> ContactRelation.Relationship.INVITED
            else -> ContactRelation.Relationship.NONE
        }
        UsernameSearchResult.Type.REQUEST_SENT -> ContactRelation.Relationship.INVITED
        UsernameSearchResult.Type.REQUEST_RECEIVED -> when (state?.status) {
            null -> ContactRelation.Relationship.INVITE_RECEIVED
            Status.LOADING -> ContactRelation.Relationship.ACCEPTING_INVITE
            Status.SUCCESS -> ContactRelation.Relationship.FRIENDS
            else -> ContactRelation.Relationship.INVITE_RECEIVED
        }
        UsernameSearchResult.Type.CONTACT_ESTABLISHED -> ContactRelation.Relationship.FRIENDS
    }
}