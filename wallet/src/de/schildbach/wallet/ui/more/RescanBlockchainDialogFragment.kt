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

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.ui.compose_views.createWalletCreationDateInfoDialog
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Dialog fragment for confirming blockchain rescan with optional wallet creation date selection.
 *
 * Figma Node ID: 2878:103004 (modal.dialogue)
 */
class RescanBlockchainDialogFragment : DialogFragment() {
    private var onConfirm: ((Boolean, Long?) -> Unit)? = null
    private var initialCreationDate: Long? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                RescanBlockchainDialog(
                    initialCreationDate = initialCreationDate,
                    onDismissRequest = {
                        onConfirm?.invoke(false, null)
                        dismiss()
                    },
                    onConfirm = { creationDate ->
                        onConfirm?.invoke(true, creationDate)
                        dismiss()
                    },
                    onShowDateInfo = {
                        createWalletCreationDateInfoDialog()
                            .show(requireActivity().supportFragmentManager, "wallet_creation_date_info")
                    }
                )
            }
        }
    }

    fun show(activity: FragmentActivity, creationDate: Long? = null, onConfirm: (Boolean, Long?) -> Unit) {
        this.initialCreationDate = creationDate
        this.onConfirm = onConfirm
        super.show(activity.supportFragmentManager, tag)
    }
}

@Composable
fun RescanBlockchainDialog(
    initialCreationDate: Long? = null,
    onDismissRequest: () -> Unit,
    onConfirm: (Long?) -> Unit,
    onShowDateInfo: () -> Unit = {}
) {
    var selectedCreationDate by remember { mutableStateOf(initialCreationDate) }
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    var datePickerDialog by remember { mutableStateOf<DatePickerDialog?>(null) }

    // Dismiss date picker when dialog is disposed (e.g., lock screen appears)
    DisposableEffect(Unit) {
        onDispose {
            datePickerDialog?.dismiss()
        }
    }

    fun showDatePicker() {
        val minDate = de.schildbach.wallet.Constants.EARLIEST_HD_SEED_CREATION_TIME * 1000L
        val maxDate = System.currentTimeMillis()

        val calendar = Calendar.getInstance()
        // Initialize with the selected creation date if available, otherwise use today
        if (selectedCreationDate != null) {
            calendar.timeInMillis = selectedCreationDate!! * 1000L
        }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        datePickerDialog = DatePickerDialog(
            context,
            { _, selectedYear, selectedMonth, selectedDay ->
                val selectedCalendar = Calendar.getInstance()
                selectedCalendar.set(selectedYear, selectedMonth, selectedDay, 0, 0, 0)
                selectedCalendar.set(Calendar.MILLISECOND, 0)
                selectedCreationDate = selectedCalendar.timeInMillis / 1000L // Convert to seconds
            },
            year,
            month,
            day
        )

        datePickerDialog?.datePicker?.minDate = minDate
        datePickerDialog?.datePicker?.maxDate = maxDate
        datePickerDialog?.setTitle(context.getString(R.string.restore_wallet_date_picker_title))
        datePickerDialog?.show()
    }

    ModalDialog(
        showDialog = true,
        onDismissRequest = onDismissRequest,
        icon = ImageVector.vectorResource(R.drawable.ic_warning_yellow_circle),
        heading = stringResource(R.string.rescan_blockchain_dialog_title),
        textBlocks = listOf(
            stringResource(R.string.rescan_blockchain_dialog_message)
        ),
        textAlign = TextAlign.Start,
        horizontalPadding = 0.dp,
        content = {
            // Wallet creation date selection field
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Label with info icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.rescan_blockchain_select_date_label),
                        style = MyTheme.Body2Regular,
                        color = MyTheme.Colors.textPrimary
                    )

                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_info_circle),
                        contentDescription = stringResource(R.string.rescan_blockchain_date_info_description),
                        tint = MyTheme.Colors.dashBlue,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onShowDateInfo)
                    )
                }

                // Date selection field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MyTheme.Colors.backgroundPrimary,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable(onClick = { showDatePicker() })
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_calendar),
                            contentDescription = null,
                            tint = MyTheme.Colors.textPrimary,
                            modifier = Modifier.size(24.dp)
                        )

                        Text(
                            text = selectedCreationDate?.let {
                                dateFormat.format(Date(it * 1000L))
                            } ?: stringResource(R.string.rescan_blockchain_select_date_placeholder),
                            style = MyTheme.Body2Regular,
                            color = MyTheme.Colors.textPrimary
                        )
                    }

                    // Clear button - only show when date is selected
                    if (selectedCreationDate != null) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_x),
                            contentDescription = stringResource(R.string.button_cancel),
                            tint = MyTheme.Colors.textSecondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(onClick = { selectedCreationDate = null })
                        )
                    }
                }
            }
        },
        buttons = listOf(
            ButtonData(
                label = stringResource(R.string.button_cancel),
                onClick = onDismissRequest,
                style = Style.PlainBlack
            ),
            ButtonData(
                label = stringResource(R.string.rescan_blockchain_confirm_button),
                onClick = { onConfirm(selectedCreationDate) },
                style = Style.FilledBlue
            )
        )
    )
}

@Composable
@Preview
fun RescanBlockchainDialogPreview() {
    RescanBlockchainDialog(
        onDismissRequest = {},
        onConfirm = {},
        onShowDateInfo = {}
    )
}

@Composable
@Preview
fun RescanBlockchainDialogWithDatePreview() {
    RescanBlockchainDialog(
        initialCreationDate = System.currentTimeMillis() / 1000,
        onDismissRequest = {},
        onConfirm = {},
        onShowDateInfo = {}
    )
}