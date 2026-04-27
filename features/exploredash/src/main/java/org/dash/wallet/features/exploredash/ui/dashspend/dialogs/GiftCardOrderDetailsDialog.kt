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

package org.dash.wallet.features.exploredash.ui.dashspend.dialogs

import android.os.Bundle
import android.view.View
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.ui.components.DashList
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarClose
import org.dash.wallet.common.ui.dialogs.ComposeBottomSheet
import org.dash.wallet.common.util.Constants
import org.dash.wallet.features.exploredash.R
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency

@AndroidEntryPoint
class GiftCardOrderDetailsDialog : ComposeBottomSheet() {
    companion object {
        private const val ARG_TRANSACTION_ID = "transactionId"

        fun newInstance(transactionId: Sha256Hash) =
            GiftCardOrderDetailsDialog().apply {
                arguments = bundleOf(ARG_TRANSACTION_ID to transactionId)
            }
    }

    override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = false

    private val viewModel by viewModels<GiftCardOrderDetailsViewModel>()

    @Composable
    override fun Content() {
        val uiState by viewModel.uiState.collectAsState()
        GiftCardOrderDetailsView(
            uiState = uiState,
            onCloseClick = { dialog?.dismiss() },
            onCardClick = { onCardClick(it) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireArguments().getSerializable(ARG_TRANSACTION_ID) as? Sha256Hash)?.let {
            viewModel.init(it)
        }
    }

    private fun onCardClick(giftCard: GiftCard) {
        GiftCardDetailsDialog
            .newInstance(giftCard.txId, cardIndex = giftCard.index)
            .show(requireActivity())
    }
}

// ─── Composable UI ───────────────────────────────────────────────────────────

@Composable
internal fun GiftCardOrderDetailsView(
    uiState: GiftCardOrderUIState,
    onCloseClick: () -> Unit = {},
    onCardClick: (GiftCard) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        NavBarClose(onCloseClick = onCloseClick)
        Spacer(Modifier.height(20.dp))
        MerchantHeader(uiState = uiState)
        Spacer(Modifier.height(20.dp))
        if (uiState.giftCards.isNotEmpty()) {
            DashList(modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
            ) {
                uiState.giftCards.forEach { card ->
                    GiftCardRow(card = card, onClick = { onCardClick(card) })
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        PoweredByFooter(serviceName = uiState.serviceName)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MerchantHeader(uiState: GiftCardOrderUIState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            uiState.merchantIcon?.let { icon ->
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                )
            }
        }
        Text(
            text = uiState.merchantName,
            style = MyTheme.Typography.LabelLarge,
            color = MyTheme.Colors.textPrimary
        )
    }
}

@Composable
private fun GiftCardRow(card: GiftCard, onClick: () -> Unit) {
    val currencyFormat = remember {
        (NumberFormat.getCurrencyInstance() as DecimalFormat).apply {
            currency = Currency.getInstance(Constants.USD_CURRENCY)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_gift_card_icon),
            contentDescription = null,
            modifier = Modifier.size(width = 40.dp, height = 26.dp)
        )
        Text(
            text = currencyFormat.format(card.price),
            style = MyTheme.Typography.BodyLargeMedium,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(R.drawable.ic_list_chevron_right),
            contentDescription = null,
            tint = MyTheme.Colors.gray,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun PoweredByFooter(serviceName: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = stringResource(R.string.purchase_powered_by),
            style = MyTheme.Typography.LabelMedium,
            color = MyTheme.Colors.textTertiary,
            textAlign = TextAlign.Center
        )
        val poweredByRes = if (serviceName == ServiceName.CTXSpend) {
            R.drawable.ic_ctx_logo_blue
        } else {
            R.drawable.ic_piggycards_logo
        }
        Image(
            painter = painterResource(poweredByRes),
            contentDescription = null,
            modifier = Modifier.height(22.dp)
        )
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

private fun fakeCard(index: Int, price: Double) = GiftCard(
    txId = Sha256Hash.ZERO_HASH,
    merchantName = "Amazon",
    price = price,
    index = index
)

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun GiftCardOrderDetailsPreview() {
    GiftCardOrderDetailsView(
        uiState = GiftCardOrderUIState(
            merchantName = "Amazon",
            serviceName = ServiceName.PiggyCards,
            giftCards = listOf(
                fakeCard(0, 50.0),
                fakeCard(1, 100.0)
            )
        )
    )
}