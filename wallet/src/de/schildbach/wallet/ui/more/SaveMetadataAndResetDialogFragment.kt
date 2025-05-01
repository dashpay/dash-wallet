package de.schildbach.wallet.ui.more

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.DialogFragment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.liveData
import androidx.work.WorkInfo
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.dashpay.utils.TransactionMetadataSettings
import de.schildbach.wallet_test.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.ui.components.ButtonData
import org.dash.wallet.common.ui.components.LimitationItem
import org.dash.wallet.common.ui.components.ModalDialog
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.util.observe
import java.util.UUID
import java.util.concurrent.TimeUnit

//@AndroidEntryPoint
//@OptIn(ExperimentalCoroutinesApi::class)
//class SaveMetadataAndResetDialogFragment : DialogFragment() {
//    val viewModel: TransactionMetadataSettingsViewModel by viewModels()
//    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
//        return Dialog(requireContext()).apply {
//            setContentView(ComposeView(context).apply {
//                setContent {
//                    //MaterialTheme {
//                        ResetWalletDialog(
//                            {},
//                            {},
//                            {
//                                viewModel.saveToNetwork(true)
//                                viewModel.lastSaveWorkId.filterNotNull().observe(viewLifecycleOwner) {
//                                    viewModel.publishOperationLiveData(it).observe(viewLifecycleOwner) { workInfo ->
//                                        if (workInfo.status == Status.SUCCESS) {
//                                            dismiss()
//                                        }
//                                    }
//                                }
//                            },
//                            {
//                                TransactionMetadataDialog.newInstance()
//                                    .show(requireActivity())
//                            },
//                            viewModel
//                        )
//                    //}
//                }
//            })
//            dialog?.setCancelable(false)
//            dialog?.setCanceledOnTouchOutside(false)
//        }
//    }

@AndroidEntryPoint
@OptIn(ExperimentalCoroutinesApi::class)
class SaveMetadataAndResetDialogFragment : DialogFragment() {
    lateinit var onResult: (Boolean?) -> Unit
    val viewModel: TransactionMetadataSettingsViewModel by viewModels()
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                ResetWalletDialog(
                    onDismissRequest = {
                        onResult(null)
                        dismiss() },
                    onResetWithoutSaving = {
                        onResult(false)
                        dismiss()
                    },
                    onSaveAndReset = {
                        saveAndReset()
                    },
                    onLearnMore = {
                        TransactionMetadataDialog.newInstance()
                            .show(requireActivity().supportFragmentManager, null)
                    },
                    viewModel
                )
            }
        }
    }

    private fun saveAndReset() {
        lifecycleScope.launch {
            val workId = viewModel.saveToNetworkNow()
            var started = false
            viewModel.publishOperationLiveData(workId).observe(viewLifecycleOwner) { workInfo ->
                Log.i("stuff", workInfo?.toString() ?: "")
                when (workInfo.status) {
                    Status.LOADING -> {
                        if (!started) {
                            started = true
                        }
                    }
                    Status.SUCCESS -> {
                        if (started) {
                            onResult(true)
                            dismiss()
                        }
                    }

                    Status.ERROR -> {
                        // show error dialog with retry button
                    }
                    Status.CANCELED -> {
                        // should not happen
                    }
                }
            }
        }
    }

    fun show(activity: FragmentActivity, onResult: (Boolean?) -> Unit) {
        this.onResult = onResult
        super.show(activity.supportFragmentManager, tag)
    }
}

@Composable
fun ResetWalletDialog(
    onDismissRequest: () -> Unit,
    onResetWithoutSaving: () -> Unit,
    onSaveAndReset: () -> Unit,
    onLearnMore: () -> Unit,
    viewModel: TransactionMetadataSettingsPreviewViewModel
) {
    val lastSaveWorkId by viewModel.lastSaveWorkId.collectAsState()
    val publishLiveData by viewModel.publishOperationLiveData(lastSaveWorkId ?: "").asFlow().collectAsState(Resource.canceled<WorkInfo>())

    ModalDialog(
        showDialog = true,
        onDismissRequest = onDismissRequest,
        icon = ImageVector.vectorResource(R.drawable.ic_info_blue),
        heading = stringResource(R.string.reset_wallet_metadata_title),
        textBlocks = listOf(
            stringResource(R.string.reset_wallet_metadata_message)
        ),
        moreInfoButton = ButtonData(
            stringResource(R.string.learn_more),
            { onLearnMore() },
            false,
            Style.FilledRed,
            enabled = true,
            progress = false
        ),
        buttons = listOf(
            ButtonData(stringResource(R.string.reset_wallet_metadata_button_without), { onResetWithoutSaving() }, false, Style.TintedGray, lastSaveWorkId == null),
            if (lastSaveWorkId != null/* && BaseWorker.extractProgress(publishLiveData.data?.progress) != -1*/) {
                ButtonData(stringResource(R.string.reset_wallet_metadata_button_saving), {  }, false, Style.FilledRed, true, progress = true)
            } else {
                ButtonData(stringResource(R.string.reset_wallet_metadata_button_with), { onSaveAndReset() }, false, Style.TintedGray)
            }
        )
    )
}

// Example usage
@Composable
@Preview
fun ExampleScreen() {
    // State to control dialog visibility
    val showDialog = remember { mutableStateOf(true) }
    val viewModel = object: TransactionMetadataSettingsPreviewViewModel {
        override val filterState: StateFlow<TransactionMetadataSettings>
                = MutableStateFlow(TransactionMetadataSettings(savePastTxToNetwork = true, saveToNetwork = true, modified = true))
        override val hasPastTransactionsToSave: StateFlow<Boolean> = MutableStateFlow(true)
        override val lastSaveWorkId = MutableStateFlow(null)
        override val lastSaveDate: StateFlow<Long> = MutableStateFlow(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2))
        override val futureSaveDate: StateFlow<Long> = MutableStateFlow(System.currentTimeMillis())
        override fun updatePreferences(settings: TransactionMetadataSettings) {}
        override fun publishOperationLiveData(workId: String) = liveData {
            emit(Resource.loading<WorkInfo>())
        }
    }
    if (showDialog.value) {
        ResetWalletDialog(
            onDismissRequest = { showDialog.value = false },
            onResetWithoutSaving = {
                // Handle reset without saving
                showDialog.value = false
            },
            onSaveAndReset = {
                // Handle save and reset
                showDialog.value = false
            },
            onLearnMore = {
                // Handle learn more action
            },
            viewModel
        )
    }

    // Rest of your screen content
}