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

package de.schildbach.wallet.ui.more

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.DarkPreviewTheme
import org.dash.wallet.common.ui.components.LightPreviewTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import java.util.Calendar
import java.util.TimeZone

/**
 * Material3 [DatePicker] hosted in a [DatePickerDialog], themed entirely from [LocalDashColors].
 *
 * @param initialCreationDate currently selected wallet creation date, in local-midnight seconds (or null)
 * @param onDateSelected invoked with the newly picked date in local-midnight seconds when the user confirms
 * @param onDismiss invoked when the picker is dismissed without confirming
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletCreationDatePickerDialog(
    initialCreationDate: Long?,
    onDateSelected: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalDashColors.current
    val earliestMillis = de.schildbach.wallet.Constants.EARLIEST_HD_SEED_CREATION_TIME * 1000L
    val nowMillis = System.currentTimeMillis()
    val minYear = remember { Calendar.getInstance().apply { timeInMillis = earliestMillis }.get(Calendar.YEAR) }
    val maxYear = remember { Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.YEAR) }

    val datePickerState = rememberDatePickerState(
        // The picker works in UTC; convert the stored local-midnight seconds to a UTC-midnight instant.
        initialSelectedDateMillis = initialCreationDate?.let { localSecondsToUtcMidnight(it) },
        yearRange = minYear..maxYear,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis in earliestMillis..nowMillis

            override fun isSelectableYear(year: Int): Boolean =
                year in minYear..maxYear
        }
    )

    val datePickerColors = DatePickerDefaults.colors(
        containerColor = colors.backgroundSecondary,
        titleContentColor = colors.textSecondary,
        headlineContentColor = colors.textPrimary,
        weekdayContentColor = colors.textSecondary,
        subheadContentColor = colors.textSecondary,
        navigationContentColor = colors.textPrimary,
        yearContentColor = colors.textPrimary,
        disabledYearContentColor = colors.contentDisabled,
        currentYearContentColor = colors.dashBlue,
        selectedYearContentColor = Color.White,
        selectedYearContainerColor = colors.dashBlue,
        dayContentColor = colors.textPrimary,
        disabledDayContentColor = colors.contentDisabled,
        selectedDayContentColor = Color.White,
        selectedDayContainerColor = colors.dashBlue,
        todayContentColor = colors.dashBlue,
        todayDateBorderColor = colors.dashBlue,
        dividerColor = colors.divider
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onDateSelected(datePickerState.selectedDateMillis?.let { utcMidnightToLocalSeconds(it) })
                }
            ) {
                Text(text = stringResource(R.string.button_ok), color = colors.dashBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.button_cancel), color = colors.dashBlue)
            }
        },
        colors = datePickerColors
    ) {
        DatePicker(
            state = datePickerState,
            title = {
                Text(
                    text = stringResource(R.string.restore_wallet_date_picker_title),
                    style = MyTheme.Body2Regular,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                )
            },
            colors = datePickerColors
        )
    }
}

/**
 * Material3's [DatePicker] operates in UTC: [androidx.compose.material3.DatePickerState.selectedDateMillis]
 * is the UTC-midnight instant of the chosen calendar day. Convert it back to the local-midnight seconds the
 * rest of the app stores and displays.
 */
private fun utcMidnightToLocalSeconds(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    val local = Calendar.getInstance().apply {
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return local.timeInMillis / 1000L
}

/** Inverse of [utcMidnightToLocalSeconds]: local-midnight seconds to the UTC-midnight millis the picker expects. */
private fun localSecondsToUtcMidnight(localSeconds: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localSeconds * 1000L }
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return utc.timeInMillis
}

@Preview(name = "DatePicker Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "DatePicker Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun WalletCreationDatePickerDialogPreview() {
    LightPreviewTheme {
        WalletCreationDatePickerDialog(
            initialCreationDate = System.currentTimeMillis() / 1000,
            onDateSelected = {},
            onDismiss = {}
        )
    }
}