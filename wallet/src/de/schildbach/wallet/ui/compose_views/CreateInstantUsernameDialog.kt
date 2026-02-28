package de.schildbach.wallet.ui.compose_views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style

fun createInstantUsernameDialog(
    onCreateInstantUsername: () -> Unit = {},
    onCancel: () -> Unit = {}
): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false,
        content = { dialog ->
            CreateInstantUsernameContent(
                onCreateClick = {
                    onCreateInstantUsername()
                    dialog.dismiss()
                },
                onCancelClick = {
                    onCancel()
                    dialog.dismiss()
                }
            )
        }
    )
}

@Composable
private fun CreateInstantUsernameContent(
    onCreateClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(top = 60.dp) // Space for the drag indicator and close button
    ) {
        // Content wrapper
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            
            // Title
            Text(
                text = stringResource(R.string.create_instant_username_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF191C1F),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Description
            Text(
                text = stringResource(R.string.create_instant_username_description),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF525C66),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(28.dp))
        }
        
        // Buttons section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Create instant username button (filled blue)
            DashButton(
                text = stringResource(R.string.create_instant_username_button),
                style = Style.FilledBlue,
                size = Size.Large,
                onClick = onCreateClick
            )
            
            // Cancel button (tinted gray)
            DashButton(
                text = stringResource(R.string.create_instant_username_cancel),
                style = Style.TintedGray,
                size = Size.Large,
                onClick = onCancelClick
            )
        }
        
        // Home indicator space
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun CreateInstantUsernameContentPreview() {
    CreateInstantUsernameContent(
        onCreateClick = { },
        onCancelClick = { }
    )
}
