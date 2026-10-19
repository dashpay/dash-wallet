package de.schildbach.wallet.ui.compose_views

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.FeatureTopText
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.util.Qr

fun createExtendedPublicKeyDialog(
    xpubWithCreationDate: String,
    xpub: String,
    onCopy: () -> Unit = {},
    onShare: (String) -> Unit = {}
): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false,
        content = { dialog ->
            ExtendedPublicKeyContent(
                xpubWithCreationDate = xpubWithCreationDate,
                xpub = xpub,
                onCopy = onCopy,
                onShare = {
                    onShare(xpubWithCreationDate)
                    dialog.dismiss()
                }
            )
        }
    )
}

@Composable
private fun ExtendedPublicKeyContent(
    xpubWithCreationDate: String,
    xpub: String,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val qrBitmap = remember(xpubWithCreationDate) { Qr.qrBitmap(xpubWithCreationDate) }
    val colors = LocalDashColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        qrBitmap?.let { bitmap ->
            // Always dark modules on a white quiet zone, whatever the app
            // theme: the bitmap has no margin and many scanners reject an
            // inverted (light-on-dark) code.
            Box(
                modifier = Modifier
                    .padding(vertical = 30.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn),
                    modifier = Modifier.size(156.dp)
                )
            }
        }

        FeatureTopText(
            modifier = Modifier.padding(horizontal = 40.dp),
            heading = stringResource(R.string.extended_public_key_fragment_title),
            textStyle = MyTheme.Typography.HeadlineMediumBold,
            showText = false
        )
        Text(
            text = xpub,
            style = MyTheme.Typography.BodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 60.dp)
                .clickable { onCopy() }
        )

        Spacer(modifier = Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 60.dp, vertical = 20.dp)
        ) {
            DashButton(
                text = stringResource(R.string.extended_public_key_share_key),
                style = Style.TintedBlue,
                size = Size.Large,
                onClick = onShare
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExtendedPublicKeyContentPreview() {
    ExtendedPublicKeyContent(
        xpubWithCreationDate = "tpubDDWFXNdndhsMu9akCJcorERilscQmkjsd?c=1234567890&h=bip44",
        xpub = "tpubDDWFXNdndhsMu9akCJcorERilscQmkjsd",
        onCopy = {},
        onShare = {}
    )
}