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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.ContactRelation
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.MyTheme

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
    onIgnoreClick: () -> Unit,
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
                FilledBlueButton(
                    text = stringResource(R.string.send_contact_request_short),
                    iconRes = null,
                    isEnabled = !isNetworkError,
                    isLoading = false,
                    onClick = onSendOrAcceptClick
                )

            ContactRelation.Relationship.INVITING ->
                FilledBlueButton(
                    text = null,
                    iconRes = null,
                    isEnabled = false,
                    isLoading = true,
                    showDisabledStyle = false,
                    onClick = {}
                )

            ContactRelation.Relationship.INVITED ->
                FilledBlueButton(
                    text = stringResource(R.string.contact_request_sent_short),
                    iconRes = null,
                    isEnabled = false,
                    isLoading = false,
                    showDisabledStyle = true,
                    onClick = {}
                )

            ContactRelation.Relationship.INVITE_RECEIVED -> {
                FilledBlueButton(
                    text = stringResource(R.string.send_button_label),
                    iconRes = R.drawable.ic_dash_d_white,
                    isEnabled = true,
                    isLoading = false,
                    onClick = onPayClick
                )
                AcceptButton(
                    isNetworkError = isNetworkError,
                    onClick = onSendOrAcceptClick
                )
            }

            ContactRelation.Relationship.ACCEPTING_INVITE ->
                FilledBlueButton(
                    text = null,
                    iconRes = null,
                    isEnabled = false,
                    isLoading = true,
                    showDisabledStyle = false,
                    onClick = {}
                )

            ContactRelation.Relationship.FRIENDS ->
                FilledBlueButton(
                    text = stringResource(R.string.send_button_label),
                    iconRes = R.drawable.ic_dash_d_white,
                    isEnabled = true,
                    isLoading = false,
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
private fun FilledBlueButton(
    text: String?,
    iconRes: Int?,
    isEnabled: Boolean,
    isLoading: Boolean,
    showDisabledStyle: Boolean = !isEnabled,
    onClick: () -> Unit
) {
    val backgroundColor = if (showDisabledStyle) MyTheme.Colors.primary5 else MyTheme.Colors.dashBlue
    val contentColor = if (showDisabledStyle) MyTheme.Colors.primary40 else Color.White

    Button(
        onClick = onClick,
        enabled = isEnabled && !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = contentColor,
            disabledContainerColor = backgroundColor,
            disabledContentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            when {
                isLoading -> CircularProgressIndicator(
                    color = contentColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(17.dp)
                )
                iconRes != null -> {
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    if (text != null) Spacer(modifier = Modifier.width(10.dp))
                }
            }
            if (text != null && !isLoading) {
                Text(
                    text = text,
                    style = MyTheme.Typography.TitleMediumSemibold,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun AcceptButton(
    isNetworkError: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !isNetworkError,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(R.color.dash_green),
            contentColor = Color.White,
            disabledContainerColor = colorResource(R.color.dash_green).copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.7f)
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.contact_request_accept),
            style = MyTheme.Typography.TitleMediumSemibold
        )
    }
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