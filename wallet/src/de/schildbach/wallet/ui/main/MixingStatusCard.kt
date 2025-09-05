package de.schildbach.wallet.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.bitcoinj.core.Coin
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.util.toBigDecimal
import java.text.DecimalFormat

@Composable
fun MixingAnimation(loop: Boolean) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.mixing_anim)
    )
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        isPlaying = loop
    )
    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = Modifier.size(32.dp)
    )
}

@Composable
fun MixingStatusCard(
    coinJoinModeFlow: Flow<CoinJoinMode>,
    statusFlow: Flow<MixingStatus>,
    percentageFlow: Flow<Double>,
    mixedBalanceFlow: Flow<Coin>,
    totalBalanceFlow: Flow<Coin>,
    hideBalanceFlow: Flow<Boolean>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val mode by coinJoinModeFlow.collectAsState(CoinJoinMode.NONE)
    val status by statusFlow.collectAsState(MixingStatus.NOT_STARTED)
    val isVisible = when (mode) {
        CoinJoinMode.NONE -> when (status) {
            MixingStatus.FINISHING -> true
            else -> false
        }
        else -> when (status) {
            MixingStatus.NOT_STARTED,
            MixingStatus.ERROR,
            MixingStatus.FINISHED -> false
            else -> true
        }
    }
    val mixingNow = when (status) {
        MixingStatus.MIXING, MixingStatus.FINISHING -> true
        else -> false
    }
    val statusTextId = when (status) {
        MixingStatus.MIXING -> {
            R.string.coinjoin_mixing
        }
        MixingStatus.FINISHING -> {
            R.string.coinjoin_mixing_finishing
        }
        MixingStatus.PAUSED -> {
            R.string.coinjoin_paused
        }
        else -> {
            R.string.error
        }
    }
    val percentageDouble by percentageFlow.collectAsState(0.0)
    val percentageInt = percentageDouble.toInt()
    val mixedBalance by mixedBalanceFlow.collectAsState(Coin.ZERO)
    val totalBalance by totalBalanceFlow.collectAsState(Coin.ZERO)
    val hideBalance by hideBalanceFlow.collectAsState(false)
    val decimalFormat = DecimalFormat("0.0000")
    val totalBalanceString = decimalFormat.format(totalBalance.toBigDecimal())
    val mixedBalanceString = decimalFormat.format(mixedBalance.toBigDecimal())

    if (isVisible) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .height(60.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = Color(0xFFB8C1CC).copy(alpha = 0.15f),
                    spotColor = Color(0xFFB8C1CC).copy(alpha = 0.15f)
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            onClick = onClick
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MixingAnimation(mixingNow)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Status and balance row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        // Status & percentage
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.coinjoin_progress_status_percentage, stringResource(statusTextId), percentageInt),
                                style = MyTheme.Caption,
                                color = MyTheme.Colors.textPrimary,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }

                        // Balance with logo
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (hideBalance) stringResource(R.string.coinjoin_progress_amount_hidden) else stringResource(R.string.coinjoin_progress_balance, mixedBalanceString, totalBalanceString),
                                style = MyTheme.Caption,
                                color = MyTheme.Colors.textPrimary,
                                textAlign = TextAlign.End
                            )

                            Icon(
                                painter = painterResource(id = R.drawable.ic_dash_d_black),
                                contentDescription = "Dash logo",
                                modifier = Modifier.size(10.47.dp),
                                tint = Color(0xFF0C0C0D)
                            )
                        }
                    }

                    // Progress bar
                    Column {
                        val progressFraction = percentageDouble.let { raw ->
                            val safe = if (raw.isNaN()) 0.0 else raw.coerceIn(0.0, 100.0)
                            (safe / 100.0).toFloat()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(
                                    color = MyTheme.Colors.extraLightGray,
                                    shape = RoundedCornerShape(4.dp)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction)
                                    .fillMaxHeight()
                                    .background(
                                        color = MyTheme.Colors.dashBlue,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

// Preview
@Preview(showBackground = true)
@Composable
fun MixingBalanceCardPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val trueFlow = MutableStateFlow(true)
            val falseFlow = MutableStateFlow(false)
            val coinJoinMode = MutableStateFlow(CoinJoinMode.INTERMEDIATE)
            val totalBalance = MutableStateFlow(Coin.valueOf(522994000))
            val totalBalanceValue by totalBalance.collectAsState()
            MixingStatusCard(
                coinJoinMode,
                MutableStateFlow(MixingStatus.MIXING),
                percentageFlow = MutableStateFlow(10.0),
                mixedBalanceFlow = MutableStateFlow(totalBalanceValue.div(10)),
                totalBalanceFlow = totalBalance,
                hideBalanceFlow = falseFlow
            )

            MixingStatusCard(
                coinJoinMode,
                MutableStateFlow(MixingStatus.FINISHING),
                percentageFlow = MutableStateFlow(99.9),
                mixedBalanceFlow = MutableStateFlow(totalBalanceValue.multiply(99).div(100)),
                totalBalanceFlow = totalBalance,
                hideBalanceFlow = trueFlow
            )

            MixingStatusCard(
                coinJoinMode,
                MutableStateFlow(MixingStatus.PAUSED),
                percentageFlow = MutableStateFlow(45.0),
                mixedBalanceFlow = MutableStateFlow(totalBalanceValue.div(2)),
                totalBalanceFlow = totalBalance,
                hideBalanceFlow = falseFlow
            )
        }
    }
}