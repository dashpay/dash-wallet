/*
 * Copyright 2023 Dash Core Group.
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
package org.dash.wallet.features.exploredash.ui.dashspend.dialogs

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.text.HtmlCompat
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.BarcodeFormat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashList
import org.dash.wallet.common.ui.components.ListItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBackClose
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.dialogs.ComposeBottomSheet
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.DeepLinkDestination
import org.dash.wallet.common.util.Qr
import org.dash.wallet.common.util.copy
import org.dash.wallet.common.util.deepLinkNavigate
import org.dash.wallet.common.util.findFragmentActivity
import org.dash.wallet.features.exploredash.BuildConfig
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.Barcode
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardStatus
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Currency

@AndroidEntryPoint
class GiftCardDetailsDialog : ComposeBottomSheet() {
    companion object {
        private const val ARG_TRANSACTION_ID = "transactionId"
        private const val ARG_CARD_INDEX = "cardIndex"
        private const val WAIT_LIMIT_FOR_ERROR = 60

        fun newInstance(transactionId: Sha256Hash, cardIndex: Int = 0) =
            GiftCardDetailsDialog().apply {
                arguments = bundleOf(
                    ARG_TRANSACTION_ID to transactionId,
                    ARG_CARD_INDEX to cardIndex
                )
            }
    }
    override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = true

    private val viewModel by viewModels<GiftCardDetailsViewModel>()
    private val ctxSpendViewModel by viewModels<DashSpendViewModel>()
    private var originalBrightness: Float = -1f

    private val bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) {}
        override fun onSlide(bottomSheet: View, slideOffset: Float) {
            if (slideOffset < -0.5) setMaxBrightness(false)
        }
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    @Composable
    override fun Content() {
        GiftCardDetailsContent(
            viewModel = viewModel,
            waitLimitForError = WAIT_LIMIT_FOR_ERROR,
            onCloseClick = { dismiss() },
            onMaxBrightness = { enable -> setMaxBrightness(enable) },
            onViewTransaction = {
                deepLinkNavigate(DeepLinkDestination.Transaction(viewModel.transactionId.toString()))
            },
            onContactSupport = { contactSupport() },
            onErrorLogged = { error, message -> ctxSpendViewModel.logError(error, message) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (requireArguments().getSerializable(ARG_TRANSACTION_ID) as? Sha256Hash)?.let { transactionId ->
            val cardIndex = requireArguments().getInt(ARG_CARD_INDEX, 0)
            viewModel.init(transactionId, cardIndex)
        }

        subscribeToBottomSheetCallback()
    }

    private fun contactSupport() {
        val error = viewModel.uiState.value.error as? CTXSpendException
        val intent = ctxSpendViewModel.createEmailIntent(
            "${error?.serviceName ?: "DashSpend"} Issue with tx: ${viewModel.transactionId.toStringBase58()}",
            sendToService = true,
            error
        )
        val chooser = Intent.createChooser(intent, getString(R.string.report_issue_dialog_mail_intent_chooser))
        launcher.launch(chooser)
    }

    private fun setMaxBrightness(enable: Boolean) {
        val window = dialog?.window ?: return
        val params = window.attributes
        if (enable) {
            if (originalBrightness < 0) originalBrightness = params.screenBrightness
            params.screenBrightness = 1.0f
        } else {
            params.screenBrightness = originalBrightness
        }
        window.attributes = params
    }

    private fun subscribeToBottomSheetCallback() {
        val bottomSheet = (dialog as BottomSheetDialog)
            .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let { BottomSheetBehavior.from(it).addBottomSheetCallback(bottomSheetCallback) }
    }

    override fun dismiss() {
        setMaxBrightness(false)
        super.dismiss()
    }

    override fun onDestroyView() {
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let { BottomSheetBehavior.from(it).removeBottomSheetCallback(bottomSheetCallback) }
        setMaxBrightness(false)
        super.onDestroyView()
    }
}

// ─── Composable UI ───────────────────────────────────────────────────────────

@Composable
private fun GiftCardDetailsContent(
    viewModel: GiftCardDetailsViewModel,
    waitLimitForError: Int,
    onCloseClick: () -> Unit,
    onMaxBrightness: (Boolean) -> Unit,
    onViewTransaction: () -> Unit,
    onContactSupport: () -> Unit,
    onErrorLogged: (Exception, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }

    LaunchedEffect(uiState.error, uiState.queries) {
        if (uiState.error != null && uiState.queries == waitLimitForError) {
            onErrorLogged(
                uiState.error!!,
                "${uiState.giftCard?.merchantName} did not deliver the card after 10 tries"
            )
        }
    }

    GiftCardDetailsView(
        uiState = uiState,
        waitLimitForError = waitLimitForError,
        onCloseClick = onCloseClick,
        onMaxBrightness = onMaxBrightness,
        onViewTransaction = onViewTransaction,
        onContactSupport = onContactSupport,
        onHowToUse = { viewModel.logEvent(AnalyticsConstants.DashSpend.HOW_TO_USE) },
        onBalanceCheck = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
        onCopyNumber = { number -> number.copy(activity, "card number") },
        onCopyPin = { pin -> pin.copy(activity, "card pin") }
    )
}

@Composable
internal fun GiftCardDetailsView(
    uiState: GiftCardUIState,
    waitLimitForError: Int = 60,
    onCloseClick: () -> Unit = {},
    onMaxBrightness: (Boolean) -> Unit = {},
    onViewTransaction: () -> Unit = {},
    onContactSupport: () -> Unit = {},
    onHowToUse: () -> Unit = {},
    onBalanceCheck: (String) -> Unit = {},
    onCopyNumber: (String) -> Unit = {},
    onCopyPin: (String) -> Unit = {}
) {
    var showHowToUse by remember { mutableStateOf(false) }

    val hasExceededWaitLimit = uiState.queries >= waitLimitForError
    val shouldShowTimeoutError = when (uiState.status) {
        GiftCardStatus.UNPAID, GiftCardStatus.PAID -> hasExceededWaitLimit
        else -> false
    }
    val shouldShowError = (shouldShowTimeoutError || uiState.status == GiftCardStatus.REJECTED) && uiState.error != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        NavBarBackClose(onBackClick = onCloseClick, onCloseClick = onCloseClick)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            MerchantHeader(uiState = uiState)

            Spacer(modifier = Modifier.height(16.dp))

            GiftCardItemCard(
                giftCard = uiState.giftCard,
                barcode = uiState.barcode,
                order = uiState.giftCard?.note,
                shouldShowError = shouldShowError,
                shouldShowTimeoutError = shouldShowTimeoutError,
                error = uiState.error,
                onMaxBrightness = onMaxBrightness,
                onBalanceCheck = { uiState.giftCard?.merchantUrl?.let { onBalanceCheck(it) } },
                onCopyNumber = onCopyNumber,
                onCopyPin = onCopyPin
            )

            Spacer(modifier = Modifier.height(12.dp))

            DashList {
                NavigationRow(
                    label = stringResource(R.string.purchase_view_transaction),
                    onClick = onViewTransaction
                )
            }

            if (shouldShowError) {
                Spacer(modifier = Modifier.height(12.dp))
                val supportLabel = when ((uiState.error as? CTXSpendException)?.serviceName) {
                    ServiceName.CTXSpend -> stringResource(R.string.gift_card_contact_ctx)
                    ServiceName.PiggyCards -> stringResource(R.string.gift_card_contact_piggycards)
                    else -> stringResource(R.string.gift_card_contact_support)
                }
                DashList {
                    NavigationRow(label = supportLabel, onClick = onContactSupport)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!showHowToUse) {
                DashButton(
                    text = stringResource(R.string.purchase_see_how_to_use_gift_card),
                    style = Style.PlainBlue,
                    onClick = {
                        onHowToUse()
                        showHowToUse = true
                    }
                )
            } else {
                HowToUseCard()
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.purchase_powered_by),
                style = MyTheme.Typography.LabelMedium,
                color = MyTheme.Colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            val poweredByRes = if (uiState.serviceName == ServiceName.CTXSpend) {
                R.drawable.ic_ctx_logo_blue
            } else {
                R.drawable.ic_piggycards_logo
            }
            Image(
                painter = painterResource(poweredByRes),
                contentDescription = null,
                modifier = Modifier
                    .height(20.dp)
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun MerchantHeader(uiState: GiftCardUIState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            val icon = uiState.icon
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(100.dp))
                )
                Image(
                    painter = painterResource(R.drawable.ic_gift_card_tx),
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color.White, CircleShape)
                        .padding(2.dp)
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_gift_card_tx),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = uiState.giftCard?.merchantName ?: "",
                style = MyTheme.Typography.TitleSmallSemibold,
                color = MyTheme.Colors.textPrimary
            )
            uiState.date?.let { date ->
                val datePart = date.format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"))
                val timePart = date.format(DateTimeFormatter.ofPattern("h:mm a"))
                Text(
                    text = stringResource(R.string.purchase_gift_card_date_format, datePart, timePart),
                    style = MyTheme.Typography.LabelMedium,
                    color = MyTheme.Colors.textTertiary
                )
            }
        }
    }
}

@Composable
private fun GiftCardItemCard(
    giftCard: GiftCard?,
    barcode: Barcode?,
    order: String?,
    shouldShowError: Boolean,
    shouldShowTimeoutError: Boolean,
    error: Exception?,
    onMaxBrightness: (Boolean) -> Unit,
    onBalanceCheck: () -> Unit,
    onCopyNumber: (String) -> Unit,
    onCopyPin: (String) -> Unit
) {
    val currencyFormat = remember {
        (NumberFormat.getCurrencyInstance() as DecimalFormat).apply {
            currency = Currency.getInstance(Constants.USD_CURRENCY)
        }
    }

    val hasNumber = !giftCard?.number.isNullOrEmpty()
    val hasPin = !giftCard?.pin.isNullOrEmpty()
    val hasMerchantUrl = !giftCard?.merchantUrl.isNullOrEmpty()
    val isLoading = giftCard != null &&
        !hasNumber &&
        !hasMerchantUrl &&
        barcode == null &&
        !shouldShowError

    DashList {
        // Barcode area
        if (!shouldShowError && !hasMerchantUrl) {
            BarcodeSection(
                barcode = barcode,
                onMaxBrightness = onMaxBrightness
            )
        }

        if (!hasMerchantUrl) {
            // Original purchase row
            ListItem(
                label = stringResource(R.string.purchase_original_purchase),
                trailingText = currencyFormat.format(giftCard?.price ?: 0.0)
            )
        } else {
            ListItem(
                label = stringResource(R.string.purchase_original_purchase),
                trailingText = currencyFormat.format(giftCard?.price ?: 0.0),
                trailingActionText = stringResource(R.string.purchase_check_current_balance),
                onTrailingActionClick = { onBalanceCheck() }
            )
        }

        // Error text
        if (shouldShowError && error != null) {
            val rawMessage = when {
                shouldShowTimeoutError -> stringResource(R.string.gift_card_delay)
                error is CTXSpendException && error.resourceString != null -> {
                    stringResource(
                        error.resourceString!!.resourceId,
                        *error.resourceString!!.args.toTypedArray()
                    )
                }
                else -> null
            }
            val errorText = rawMessage?.let {
                HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
            } ?: stringResource(R.string.gift_card_details_error)

            ListItem(title = errorText)
        }

        // Card number row
        if (hasNumber && !shouldShowError) {
            ListItem(
                label = stringResource(R.string.purchase_card_number),
                trailingText = giftCard.number ?: "",
                trailingTrailingIcon = {
                    IconButton(
                        onClick = { onCopyNumber(giftCard.number!!) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy_blue),
                            contentDescription = null,
                            tint = MyTheme.Colors.dashBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }

        // Card PIN row
        if (hasPin && !shouldShowError) {
            ListItem(
                label = stringResource(R.string.purchase_card_pin),
                trailingText = giftCard.pin ?: "",
                trailingTrailingIcon = {
                    IconButton(
                        onClick = { onCopyPin(giftCard.pin!!) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy_blue),
                            contentDescription = null,
                            tint = MyTheme.Colors.dashBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }

        if (BuildConfig.DEBUG && order != null) {
            // there are UI bugs with this
            if (order.isNotEmpty()) {
                ListItem(
                    label = stringResource(R.string.purchase_order_number),
                    trailingText = order,
                    trailingTrailingIcon = {
                        IconButton(
                            onClick = { onCopyPin(order) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy_blue),
                                contentDescription = null,
                                tint = MyTheme.Colors.dashBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    onTrailingActionClick = { onCopyNumber(order) }
                )
            }
        }

        // Cashier instructions
        if ((hasNumber || hasPin) && !shouldShowError) {
            ListItem(
                helpTextAbove = stringResource(R.string.purchase_cashier_instructions),
                title = stringResource(R.string.purchase_cashier_instructions_body)
            )
        }

        // Loading indicator
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MyTheme.Colors.dashBlue,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun CardFieldRow(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MyTheme.Typography.LabelMediumMedium,
            color = MyTheme.Colors.textTertiary,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MyTheme.Typography.LabelMedium,
                color = MyTheme.Colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp)
            )
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy_blue),
                    contentDescription = null,
                    tint = MyTheme.Colors.dashBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun BarcodeSection(
    barcode: Barcode?,
    onMaxBrightness: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    var barcodeBitmap by remember(barcode) { mutableStateOf<Bitmap?>(null) }
    var barcodeError by remember(barcode) { mutableStateOf(false) }
    var isQrCode by remember(barcode) { mutableStateOf(false) }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val barcodeHeightPx = with(density) { 108.dp.toPx().toInt() }

    LaunchedEffect(barcode, containerWidthPx) {
        if (barcode != null && containerWidthPx > 0) {
            barcodeError = false
            barcodeBitmap = null
            val bitmap = try {
                withContext(Dispatchers.Default) {
                    Qr.bitmap(barcode.value, barcode.barcodeFormat, Size(containerWidthPx, barcodeHeightPx))
                }
            } catch (_: Exception) {
                null
            }
            isQrCode = barcode.barcodeFormat == BarcodeFormat.QR_CODE
            if (bitmap != null) {
                barcodeBitmap = bitmap
                onMaxBrightness(true)
            } else {
                barcodeError = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 8.dp, end = 8.dp)
            .onGloballyPositioned { containerWidthPx = it.size.width }
    ) {

        when {
            barcodeError -> {
                PlaceholderBox(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.gift_card_barcode_failed),
                        style = MyTheme.Typography.BodySmall,
                        color = MyTheme.Colors.red,
                        textAlign = TextAlign.Center
                    )
                }
            }

            barcodeBitmap != null -> {
                val barcodeHeight = if (isQrCode) 160.dp else 112.dp
                Image(
                    bitmap = barcodeBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barcodeHeight)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            barcode != null -> {
                // Barcode exists but bitmap still computing
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MyTheme.Colors.dashBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            else -> {
                // No barcode yet – show placeholder text
                PlaceholderBox(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.barcode_placeholder),
                        style = MyTheme.Typography.BodySmall,
                        color = MyTheme.Colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MyTheme.Colors.backgroundPrimary)
            .padding(vertical = 24.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun NavigationRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MyTheme.Typography.TitleSmallMedium,
            color = MyTheme.Colors.textPrimary
        )
        Icon(
            painter = painterResource(R.drawable.ic_light_gray_arrow_right),
            contentDescription = null,
            tint = MyTheme.Colors.gray,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun HowToUseCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MyTheme.Colors.backgroundSecondary)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp)
    ) {
        Text(
            text = stringResource(R.string.purchase_how_to_use_gift_card),
            style = MyTheme.Typography.LabelMediumMedium,
            color = MyTheme.Colors.textTertiary,
            maxLines = 1,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )

        HowToUseItem(
            iconRes = R.drawable.ic_self_checkout_blue,
            title = stringResource(R.string.purchase_self_checkout),
            description = stringResource(R.string.purchase_Request_assistance)
        )

        HowToUseItem(
            iconRes = R.drawable.ic_instore_blue,
            title = stringResource(R.string.purchase_in_store),
            description = stringResource(R.string.purchase_tell_the_cashier)
        )

        HowToUseItem(
            iconRes = R.drawable.ic_online_blue,
            title = stringResource(R.string.explore_online_merchant),
            description = stringResource(R.string.purchase_in_the_payment_section)
        )
    }
}

@Composable
private fun HowToUseItem(iconRes: Int, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        verticalAlignment = Alignment.Top
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .size(26.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MyTheme.Typography.TitleSmallSemibold,
                color = MyTheme.Colors.textPrimary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MyTheme.Typography.BodySmall,
                color = MyTheme.Colors.textTertiary
            )
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

private fun previewState(vararg cards: GiftCard) = GiftCardUIState(
    giftCards = cards.toList(),
    barcodes = cards.toList().map {
        Barcode(it.barcodeValue!!, it.barcodeFormat!!)
    },
    date = LocalDateTime.of(2024, 8, 26, 10, 56),
    serviceName = ServiceName.CTXSpend
)

private fun fakeCard(
    index: Int,
    number: String? = null,
    pin: String? = null,
    barcode: String? = null,
    barcodeFormat: BarcodeFormat? = null,
    merchantUrl: String? = null
) = GiftCard(
    txId = Sha256Hash.ZERO_HASH,
    merchantName = "Target",
    price = 25.00,
    number = number,
    pin = pin,
    merchantUrl = merchantUrl,
    barcodeValue = barcode,
    barcodeFormat = barcodeFormat,
    index = index,
    note = "5x49g"
)

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun RenderedBarcodePreview() {
    GiftCardDetailsView(
        uiState = previewState(
            fakeCard(index = 1, number = "6006491727005748", pin = "1411", barcode = "6006491727005748", barcodeFormat = BarcodeFormat.CODE_128),
            ),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun LoadingPreview() {
    GiftCardDetailsView(
        uiState = GiftCardUIState(
            giftCards = listOf(fakeCard(index = 0)),
            date = LocalDateTime.of(2024, 8, 26, 10, 56),
            serviceName = ServiceName.CTXSpend
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun ClaimLinkPreview() {
    GiftCardDetailsView(
        uiState = GiftCardUIState(
            giftCards = listOf(fakeCard(index = 0, merchantUrl = "https://dash.org")),
            date = LocalDateTime.of(2024, 8, 26, 10, 56),
            serviceName = ServiceName.CTXSpend
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun ErrorPreview() {
    GiftCardDetailsView(
        uiState = GiftCardUIState(
            giftCards = listOf(fakeCard(index = 0)),
            date = LocalDateTime.of(2024, 8, 26, 10, 56),
            serviceName = ServiceName.CTXSpend,
            status = GiftCardStatus.REJECTED,
            error = CTXSpendException("error", ServiceName.CTXSpend, 401, "unknown error", IllegalArgumentException("unknown"))
        )
    )
}