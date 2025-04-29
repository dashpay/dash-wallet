/*
 * Copyright 2025 Dash Core Group.
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

package de.schildbach.wallet.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import de.schildbach.wallet_test.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.components.ButtonLarge
import org.dash.wallet.common.ui.components.ButtonStyles
import org.dash.wallet.common.ui.components.DashCheckbox
import org.dash.wallet.common.ui.components.DashRadioButton
import org.dash.wallet.common.ui.components.MyTheme
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun TransactionMetadataScreen(
    onBackClick: () -> Unit,
    onInfoButtonClick: () -> Unit,
    onSaveToNetwork: () -> Unit
) {
    val backgroundColor = MyTheme.Colors.backgroundPrimary
    val primaryTextColor = MyTheme.Colors.textPrimary
    val secondaryTextColor = MyTheme.Colors.textSecondary
    val scrollState = rememberScrollState()

    // State for checkboxes
    var pastTransactionsChecked by remember { mutableStateOf(true) }
    var futureTransactionsChecked by remember { mutableStateOf(false) }
    var paymentCategoriesChecked by remember { mutableStateOf(true) }
    var taxCategoriesChecked by remember { mutableStateOf(true) }
    var fiatPricesChecked by remember { mutableStateOf(true) }
    var privateMemoChecked by remember { mutableStateOf(true) }
    var giftCardsDataChecked by remember { mutableStateOf(true) }

    // State for radio buttons
    var selectedFrequency by remember { mutableStateOf(0) } // 0 = After 10 transactions, 1 = Once a week, 2 = After every transaction

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(painter = painterResource(id = R.drawable.ic_chevron), contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onInfoButtonClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = "Info",
                            tint = MyTheme.Colors.dashBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = backgroundColor
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(20.dp))
                ButtonLarge(
                    onClick = { /* Save action */ },
                    modifier = Modifier
                        .padding(20.dp, 0.dp)
                        .fillMaxWidth(),
                    colors = ButtonStyles.blueWithWhiteText(),
                    textId = R.string.transaction_metadata_save_to_network
                )
                Spacer(modifier = Modifier.height(20.dp))
                // Home indicator
                Box(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .width(134.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(primaryTextColor.copy(alpha = 0.3f))
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(backgroundColor)
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 60.dp)
        ) {
            // Title and description
            Text(
                text = stringResource(R.string.transaction_metadata_title),
                color = primaryTextColor,
                style = MyTheme.H5Bold
            )
            Text(
                text = stringResource(R.string.transaction_metadata_description),
                modifier = Modifier.padding(top = 4.dp),
                color = secondaryTextColor,
                style = MyTheme.Body2Regular
            )

            Spacer(modifier = Modifier.height(20.dp))

            // SECTION 1: Select transactions types
            SectionTitle("Select transactions types")
            CardSection {
                // Past transactions checkbox
                DashCheckbox(
                    checked = pastTransactionsChecked,
                    onCheckedChange = { pastTransactionsChecked = it },
                    title = stringResource(R.string.transaction_metadata_past_title),
                    subtitle = stringResource(R.string.transaction_metadata_past_subtitle, Date(System.currentTimeMillis()))
                )
                // Future transactions checkbox
                DashCheckbox(
                    checked = futureTransactionsChecked,
                    onCheckedChange = { futureTransactionsChecked = it },
                    title = stringResource(R.string.transaction_metadata_future_title),
                    subtitle = stringResource(R.string.transaction_metadata_future_subtitle, Date(System.currentTimeMillis()))
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // SECTION 2: How often to save?
            SectionTitle(R.string.transaction_metadata_save_frequency)
            CardSection {
                // Radio buttons for frequency
                stringArrayResource(R.array.transaction_metadata_save_frequency).forEachIndexed { index, s ->
                    DashRadioButton(
                        selected = selectedFrequency == index,
                        onClick = { selectedFrequency = index },
                        text = s
                    )
                }

//                DashRadioButton(
//                    selected = selectedFrequency == 1,
//                    onClick = { selectedFrequency = 1 },
//                    text = "Once a week"
//                )
//                DashRadioButton(
//                    selected = selectedFrequency == 2,
//                    onClick = { selectedFrequency = 2 },
//                    text = "After every transaction"
//                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // SECTION 3: What to save?
            SectionTitle(R.string.transaction_metadata_what_to_save)
            CardSection {
                // Checkboxes for data types
                DashCheckbox(
                    checked = paymentCategoriesChecked,
                    onCheckedChange = { paymentCategoriesChecked = it },
                    title = "Payment categories"
                )
                DashCheckbox(
                    checked = taxCategoriesChecked,
                    onCheckedChange = { taxCategoriesChecked = it },
                    title = "Tax categories"
                )
                DashCheckbox(
                    checked = fiatPricesChecked,
                    onCheckedChange = { fiatPricesChecked = it },
                    title = "Fiat prices"
                )
                DashCheckbox(
                    checked = privateMemoChecked,
                    onCheckedChange = { privateMemoChecked = it },
                    title = "Private memos"
                )
                DashCheckbox(
                    checked = giftCardsDataChecked,
                    onCheckedChange = { giftCardsDataChecked = it },
                    title = "Gift cards data"
                )
            }
        }
    }
}

@Composable
fun SectionTitle(titleId: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 8.dp)
    ) {
        Text(
            text = stringResource(titleId),
            color = MyTheme.Colors.textSecondary,
            style = MyTheme.CaptionMedium
        )
    }
}

@Composable
fun SectionTitle(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 8.dp)
    ) {
        Text(
            text = title,
            color = MyTheme.Colors.textSecondary,
            style = MyTheme.CaptionMedium
        )
    }
}

@Composable
fun CardSection(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(6.dp)
        ) {
            content()
        }
    }
}

//@Composable
//fun CheckboxRow(
//    checked: Boolean,
//    onCheckedChange: (Boolean) -> Unit,
//    title: String,
//    subtitle: String? = null
//) {
//    Row(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 8.dp, horizontal = 10.dp),
//        verticalAlignment = Alignment.CenterVertically
//    ) {
//        Column(
//            modifier = Modifier.weight(1f)
//        ) {
//            Text(
//                text = title,
//                fontSize = 13.sp,
//                fontWeight = FontWeight.Medium,
//                color = Color(0xFF191C1F)
//            )
//            if (subtitle != null) {
//                Text(
//                    text = subtitle,
//                    fontSize = 12.sp,
//                    color = Color(0xFF75808A)
//                )
//            }
//        }
//
//        Box(
//            modifier = Modifier
//                .size(22.dp)
//                .background(
//                    color = if (checked) Color(0xFF008DE4) else Color.White,
//                    shape = RoundedCornerShape(6.dp)
//                ),
//            contentAlignment = Alignment.Center
//        ) {
//            if (checked) {
//                // Checkmark
//                Text(
//                    text = "✓",
//                    color = Color.White,
//                    fontSize = 16.sp
//                )
//            }
//        }
//    }
//}

//@Composable
//fun RadioButtonRow(
//    selected: Boolean,
//    onClick: () -> Unit,
//    text: String
//) {
//    Row(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 8.dp, horizontal = 10.dp),
//        verticalAlignment = Alignment.CenterVertically
//    ) {
//        Text(
//            text = text,
//            fontSize = 13.sp,
//            fontWeight = FontWeight.Medium,
//            color = Color(0xFF191C1F),
//            modifier = Modifier.weight(1f)
//        )
//
//        Box(
//            modifier = Modifier
//                .size(22.dp)
//                .padding(1.dp)
//                .background(
//                    color = Color.White,
//                    shape = CircleShape
//                )
//                .padding(1.dp)
//                .border(
//                    width = 1.5.dp,
//                    color = if (selected) Color(0xFF008DE4) else Color(0xB0B6BC).copy(alpha = 0.5f),
//                    shape = CircleShape
//                ),
//            contentAlignment = Alignment.Center
//        ) {
//            if (selected) {
//                Box(
//                    modifier = Modifier
//                        .size(12.dp)
//                        .background(
//                            color = Color(0xFF008DE4),
//                            shape = CircleShape
//                        )
//                )
//            }
//        }
//    }
//}

@Preview(showBackground = true)
@Composable
fun TransactionMetadataScreenPreview() {
    TransactionMetadataScreen({}, {}, {})
}
