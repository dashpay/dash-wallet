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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.asFlow
import androidx.lifecycle.liveData
import androidx.work.WorkInfo
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.dashpay.utils.TransactionMetadataSettings
import de.schildbach.wallet_test.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.checkerframework.checker.units.qual.s
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.ui.components.ButtonLarge
import org.dash.wallet.common.ui.components.ButtonStyles
import org.dash.wallet.common.ui.components.DashCheckbox
import org.dash.wallet.common.ui.components.DashRadioButton
import org.dash.wallet.common.ui.components.MyTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun TransactionMetadataSettingsScreen(
    onBackClick: () -> Unit,
    onInfoButtonClick: () -> Unit,
    onSaveToNetwork: () -> Unit,
    viewModel: TransactionMetadataSettingsPreviewViewModel
) {
    val coroutineScope = rememberCoroutineScope()

    val backgroundColor = MyTheme.Colors.backgroundPrimary
    val primaryTextColor = MyTheme.Colors.textPrimary
    val secondaryTextColor = MyTheme.Colors.textSecondary
    val scrollState = rememberScrollState()
    val dateFormat = SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM)

    val filterState by viewModel.filterState.collectAsState()
    val lastSaveWorkId by viewModel.lastSaveWorkId.collectAsState()
    val lastSaveDate by viewModel.lastSaveDate.collectAsState()
    val futureSaveDate by viewModel.futureSaveDate.collectAsState()
    val hasPastTransactionsToSave by viewModel.hasPastTransactionsToSave.collectAsState()
    val publishLiveData by viewModel.publishOperationLiveData(lastSaveWorkId ?: "").asFlow().collectAsState(Resource.canceled<WorkInfo>())

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
                // TODO: Why is this bar white
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Unspecified
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
                    onClick = { onSaveToNetwork() },
                    modifier = Modifier
                        .padding(20.dp, 0.dp)
                        .fillMaxWidth(),
                    colors = ButtonStyles.blueWithWhiteText(),
                    textId = if (filterState.modified && !filterState.saveToNetwork) {
                        R.string.transaction_metadata_save_to_network
                    } else {
                        R.string.save_changes
                    },
                    enabled = filterState.modified || (filterState.saveToNetwork && futureSaveDate == 0L)
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
                    checked = filterState.savePastTxToNetwork,
                    onCheckedChange = {
                        viewModel.updatePreferences(filterState.copy(savePastTxToNetwork = it))
                    },
                    title = stringResource(R.string.transaction_metadata_past_title),
                    subtitle = if (BaseWorker.extractProgress(publishLiveData.data?.progress) != -1) {
                        stringResource(R.string.transaction_metadata_past_syncing, dateFormat.format(Date(lastSaveDate)))
                    } else if (hasPastTransactionsToSave) {
                        stringResource(R.string.transaction_metadata_past_subtitle, dateFormat.format(Date(System.currentTimeMillis())))
                    } else {
                        stringResource(R.string.transaction_metadata_past_already_saved, dateFormat.format(Date(lastSaveDate)))
                    },
                    enabled = hasPastTransactionsToSave
                )
                // Future transactions checkbox
                DashCheckbox(
                    checked = filterState.saveToNetwork,
                    onCheckedChange = {
                        viewModel.updatePreferences(filterState.copy(saveToNetwork = it))
                    },
                    title = stringResource(R.string.transaction_metadata_future_title),
                    subtitle = stringResource(R.string.transaction_metadata_future_subtitle, dateFormat.format(Date(futureSaveDate)))
                )
            }

            if (filterState.saveToNetwork) {
                Spacer(modifier = Modifier.height(20.dp))

                // SECTION 2: How often to save?
                SectionTitle(R.string.transaction_metadata_save_frequency)
                CardSection {
                    // Radio buttons for frequency
                    stringArrayResource(R.array.transaction_metadata_save_frequency).forEachIndexed { index, s ->
                        DashRadioButton(
                            selected = index == filterState.saveFrequency.ordinal,
                            onClick = {
                                viewModel.updatePreferences(filterState.copy(saveFrequency = TxMetadataSaveFrequency.entries[index]))
                            },
                            text = s
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // SECTION 3: What to save?
                SectionTitle(R.string.transaction_metadata_what_to_save)
                CardSection {
                    // Checkboxes for data types
                    DashCheckbox(
                        checked = filterState.savePaymentCategory,
                        onCheckedChange = {
                            viewModel.updatePreferences(filterState.copy(savePaymentCategory = it))
                        },
                        title = stringResource(R.string.transaction_metadata_payment_category)
                    )
                    DashCheckbox(
                        checked = filterState.saveTaxCategory,
                        onCheckedChange = {
                            viewModel.updatePreferences(filterState.copy(saveTaxCategory = it))
                        },
                        title = stringResource(R.string.transaction_metadata_tax_category)
                    )
                    DashCheckbox(
                        checked = filterState.saveExchangeRates,
                        onCheckedChange = {
                            viewModel.updatePreferences(filterState.copy(saveExchangeRates = it))
                        },
                        title = stringResource(R.string.transaction_metadata_fiat_prices)
                    )
                    DashCheckbox(
                        checked = filterState.savePrivateMemos,
                        onCheckedChange = {
                            viewModel.updatePreferences(filterState.copy(savePrivateMemos = it))
                        },
                        title = stringResource(R.string.private_memo)
                    )
                    DashCheckbox(
                        checked = filterState.saveGiftcardInfo,
                        onCheckedChange = {
                            viewModel.updatePreferences(filterState.copy(saveGiftcardInfo = it))
                        },
                        title = stringResource(R.string.transaction_metadata_gift_card_data)
                    )
                }
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
        )
    ) {
        Column(
            modifier = Modifier.padding(6.dp)
        ) {
            content()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TransactionMetadataScreenPreview() {
    val viewModel = object: TransactionMetadataSettingsPreviewViewModel {
        override val filterState: StateFlow<TransactionMetadataSettings>
            = MutableStateFlow(TransactionMetadataSettings(savePastTxToNetwork = true, saveToNetwork = true, modified = true))
        override val hasPastTransactionsToSave: StateFlow<Boolean> = MutableStateFlow(true)
        override val lastSaveWorkId = MutableStateFlow(UUID.randomUUID().toString())
        override val lastSaveDate: StateFlow<Long> = MutableStateFlow(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2))
        override val futureSaveDate: StateFlow<Long> = MutableStateFlow(System.currentTimeMillis())
        override fun updatePreferences(settings: TransactionMetadataSettings) {}
        override fun publishOperationLiveData(workId: String) = liveData {
            emit(Resource.canceled<WorkInfo>())
        }
    }
    TransactionMetadataSettingsScreen({}, {}, {}, viewModel)
}
