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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.ContactRelation
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.MyTheme

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
        modifier = modifier
            .fillMaxWidth()
            .background(colorResource(R.color.dash_white))
    ) {
        when (relationship) {
            ContactRelation.Relationship.NONE ->
                MainActionButton(
                    text = stringResource(R.string.send_contact_request),
                    iconRes = R.drawable.ic_add_contact_white,
                    isLoading = false,
                    isEnabled = !isNetworkError,
                    onClick = onSendOrAcceptClick
                )

            ContactRelation.Relationship.INVITING ->
                MainActionButton(
                    text = stringResource(R.string.sending_contact_request),
                    iconRes = null,
                    isLoading = true,
                    isEnabled = false,
                    onClick = {}
                )

            ContactRelation.Relationship.INVITED ->
                MainActionButton(
                    text = stringResource(R.string.contact_request_pending),
                    iconRes = R.drawable.ic_pending_contact_request,
                    isLoading = false,
                    isEnabled = false,
                    onClick = {}
                )

            ContactRelation.Relationship.INVITE_RECEIVED -> {
                PayActionButton(onClick = onPayClick)
                AcceptIgnoreRow(
                    username = username,
                    isNetworkError = isNetworkError,
                    onAcceptClick = onSendOrAcceptClick,
                    onIgnoreClick = onIgnoreClick
                )
            }

            ContactRelation.Relationship.ACCEPTING_INVITE ->
                MainActionButton(
                    text = stringResource(R.string.accepting_contact_request),
                    iconRes = null,
                    isLoading = true,
                    isEnabled = false,
                    onClick = {}
                )

            ContactRelation.Relationship.FRIENDS ->
                PayActionButton(onClick = onPayClick)
        }

        val disclaimerText = disclaimerForRelationship(relationship, username)
        if (disclaimerText != null) {
            ContactHistoryDisclaimer(text = disclaimerText)
        }
    }
}

@Composable
private fun MainActionButton(
    text: String,
    iconRes: Int?,
    isLoading: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isEnabled) MyTheme.Colors.dashBlue else MyTheme.Colors.extraLightGray
    val contentColor = if (isEnabled) Color.White else MyTheme.Colors.darkGray

    Button(
        onClick = onClick,
        enabled = isEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 9.dp)
            .height(40.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = contentColor,
            disabledContainerColor = backgroundColor,
            disabledContentColor = contentColor
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = contentColor,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        } else if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(
            text = text,
            style = MyTheme.Typography.BodyMediumMedium,
            color = contentColor
        )
    }
}

@Composable
private fun PayActionButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp, vertical = 9.dp)
            .height(40.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MyTheme.Colors.dashBlue,
            contentColor = Color.White
        )
    ) {
        Text(
            text = stringResource(R.string.pay),
            style = MyTheme.Typography.BodyMediumMedium,
            color = Color.White
        )
    }
}

@Composable
private fun AcceptIgnoreRow(
    username: String,
    isNetworkError: Boolean,
    onAcceptClick: () -> Unit,
    onIgnoreClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.contact_request_received_title, username),
            style = MyTheme.Caption,
            color = MyTheme.Colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 34.dp, vertical = 26.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 14.dp)
        ) {
            Button(
                onClick = onAcceptClick,
                enabled = !isNetworkError,
                modifier = Modifier
                    .width(120.dp)
                    .height(39.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.dash_green),
                    contentColor = Color.White,
                    disabledContainerColor = colorResource(R.color.dash_green).copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                )
            ) {
                Text(
                    text = stringResource(R.string.contact_request_accept),
                    style = MyTheme.Typography.BodyMediumMedium
                )
            }
            Button(
                onClick = onIgnoreClick,
                modifier = Modifier
                    .width(120.dp)
                    .height(39.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MyTheme.Colors.extraLightGray,
                    contentColor = MyTheme.Colors.textPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.contact_request_ignore),
                    style = MyTheme.Typography.BodyMediumMedium
                )
            }
        }
    }
}

@Composable
private fun ContactHistoryDisclaimer(text: AnnotatedString) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 9.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(
                width = 1.dp,
                color = MyTheme.Colors.divider,
                shape = RoundedCornerShape(8.dp)
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ic_add_stranger),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 32.dp)
                .size(48.dp)
        )
        Text(
            text = text,
            style = MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp, vertical = 24.dp)
        )
    }
}

@Composable
private fun disclaimerForRelationship(
    relationship: ContactRelation.Relationship,
    username: String
): AnnotatedString? {
    val resId = when (relationship) {
        ContactRelation.Relationship.NONE -> R.string.contact_history_disclaimer
        ContactRelation.Relationship.INVITING,
        ContactRelation.Relationship.INVITED -> R.string.contact_history_disclaimer_pending
        else -> return null
    }
    val raw = stringResource(resId).replace("%", username)
    return parseSimpleHtml(raw)
}

private fun parseSimpleHtml(html: String): AnnotatedString = buildAnnotatedString {
    val boldRegex = Regex("<b>(.*?)</b>")
    var cursor = 0
    boldRegex.findAll(html).forEach { match ->
        if (match.range.first > cursor) {
            append(html.substring(cursor, match.range.first))
        }
        withBoldStyle { append(match.groupValues[1]) }
        cursor = match.range.last + 1
    }
    if (cursor < html.length) {
        append(html.substring(cursor))
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withBoldStyle(block: () -> Unit) {
    val start = length
    block()
    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
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