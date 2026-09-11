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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarClose
import org.dash.wallet.common.ui.dialogs.ComposeBottomSheet
import org.dash.wallet.common.util.Qr

/**
 * "My QR" — the counterpart of the scan button on the add-contact screen:
 * renders the current user's `dashpay://user` link (identity id + preferred
 * username) as a QR code another Dash Wallet can scan to open the
 * send-contact-request sheet for this user. Mirrors iOS `MyDashPayUserQRSheet`.
 */
class MyQrCodeBottomSheet : ComposeBottomSheet() {

    companion object {
        private const val ARG_DISPLAY_NAME = "arg_display_name"
        private const val ARG_USERNAME = "arg_username"
        private const val ARG_QR_CONTENT = "arg_qr_content"

        fun newInstance(displayName: String, username: String, qrContent: String): MyQrCodeBottomSheet {
            return MyQrCodeBottomSheet().apply {
                arguments = bundleOf(
                    ARG_DISPLAY_NAME to displayName,
                    ARG_USERNAME to username,
                    ARG_QR_CONTENT to qrContent
                )
            }
        }
    }

    override val backgroundStyle: Int = R.style.PrimaryBackground

    @Composable
    override fun Content() {
        val args = requireArguments()
        MyQrCodeContent(
            displayName = args.getString(ARG_DISPLAY_NAME).orEmpty(),
            username = args.getString(ARG_USERNAME).orEmpty(),
            qrContent = args.getString(ARG_QR_CONTENT).orEmpty(),
            onCloseClick = { dismiss() }
        )
    }
}

@Composable
private fun MyQrCodeContent(
    displayName: String,
    username: String,
    qrContent: String,
    onCloseClick: () -> Unit
) {
    val colors = LocalDashColors.current
    val qrBitmap = remember(qrContent) {
        if (qrContent.isEmpty()) null else Qr.qrBitmap(qrContent)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.backgroundPrimary),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NavBarClose(onCloseClick = onCloseClick)
        Text(
            text = displayName.ifEmpty { username },
            style = MyTheme.Typography.TitleMediumMedium,
            color = colors.textPrimary
        )
        if (displayName.isNotEmpty()) {
            Text(
                text = username,
                style = MyTheme.Typography.LabelMedium,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.search_user_my_qr_caption),
                // The shared Qr helper emits an ALPHA_8 mask meant for theme-aware
                // tinting; force it black so it stays scannable on the white card
                // no matter which theme is active.
                colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn),
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(20.dp))
                    // Deliberately white in dark mode too — camera scanners need
                    // the contrast (matches the iOS sheet).
                    .background(Color.White)
                    .padding(20.dp)
                    .size(240.dp),
                filterQuality = FilterQuality.None
            )
        }
        Text(
            text = stringResource(R.string.search_user_my_qr_caption),
            style = MyTheme.Typography.TitleSmall,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, start = 32.dp, end = 32.dp)
        )
        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Preview(showBackground = true, widthDp = 428)
@Composable
private fun MyQrCodeContentPreview() {
    DashWalletTheme {
        MyQrCodeContent(
            displayName = "John Doe",
            username = "johndoe",
            qrContent = "dashpay://user?id=6wEeJqvGgeZfLHqQHVXzXbsFY2Sbd3LHLTj7VNz6MRZ9&username=johndoe",
            onCloseClick = {}
        )
    }
}
