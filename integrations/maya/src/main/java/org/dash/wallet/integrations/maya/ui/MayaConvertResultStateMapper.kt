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

package org.dash.wallet.integrations.maya.ui

import androidx.annotation.StringRes
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.model.MayaResultType
import org.dash.wallet.integrations.maya.model.TransactionType

/**
 * Resource-based description of the result screen for one transaction outcome. The fragment
 * resolves the resource IDs against a Context; keeping the mapping free of Context makes the
 * whole TransactionType × outcome matrix unit-testable on the JVM.
 */
data class ResultSpec(
    val resultType: MayaResultType,
    val isSuccess: Boolean,
    @StringRes val titleRes: Int,
    /** Message as a resource (with optional [messageArgs]); ignored when [messageText] is set. */
    @StringRes val messageRes: Int? = null,
    val messageArgs: List<String> = emptyList(),
    /** Raw message from the backend, shown verbatim. */
    val messageText: String? = null,
    val showContactSupport: Boolean,
    @StringRes val buttonTextRes: Int
)

/** Explorer link for a successful swap: which explorer, and the URL template + its argument. */
data class ExplorerSpec(
    @StringRes val linkTextRes: Int,
    /** URL string resource; a plain URL when [urlArg] is null, a format template otherwise. */
    @StringRes val urlRes: Int,
    val urlArg: String? = null
)

/**
 * Pure mapping from a finished transaction (type + outcome) to what the result screen shows.
 * Mirrors the behavior of the old view-based MayaConvertResultFragment 1:1.
 */
object MayaConvertResultStateMapper {

    /**
     * Builds the result spec for the given transaction outcome.
     *
     * @param sendToWalletError the localized send_to_wallet_error string — a deposit error
     *        message containing it is shown verbatim, any other deposit error gets generic copy.
     * @param conversionSource/[conversionDestination] currency names for the conversion-success
     *        message ("It could take up to 5 minutes ... %s ... %s").
     */
    fun buildResultSpec(
        type: TransactionType,
        isSuccess: Boolean,
        errorMessage: String?,
        sendToWalletError: String,
        conversionSource: String,
        conversionDestination: String
    ): ResultSpec {
        return if (isSuccess) {
            when (type) {
                TransactionType.BuyDash -> ResultSpec(
                    resultType = MayaResultType.DEPOSIT_SUCCESS,
                    isSuccess = true,
                    titleRes = R.string.purchase_successful,
                    messageRes = R.string.maya_it_could_take_up_to_2_3_minutes,
                    showContactSupport = false,
                    buttonTextRes = R.string.button_close
                )
                TransactionType.BuySwap,
                TransactionType.SellSwap -> ResultSpec(
                    resultType = MayaResultType.CONVERSION_SUCCESS,
                    isSuccess = true,
                    titleRes = R.string.conversion_successful,
                    messageRes = R.string.maya_it_could_take_up_to_5_minutes,
                    messageArgs = listOf(conversionSource, conversionDestination),
                    showContactSupport = false,
                    buttonTextRes = R.string.button_close
                )
                TransactionType.TransferDash -> ResultSpec(
                    resultType = MayaResultType.TRANSFER_DASH_SUCCESS,
                    isSuccess = true,
                    titleRes = R.string.transfer_dash_successful,
                    messageRes = R.string.maya_it_could_take_up_to_10_minutes,
                    showContactSupport = false,
                    buttonTextRes = R.string.button_close
                )
            }
        } else {
            when (type) {
                TransactionType.BuyDash -> ResultSpec(
                    resultType = MayaResultType.DEPOSIT_ERROR,
                    isSuccess = false,
                    titleRes = R.string.transfer_failed,
                    // Only the send-to-wallet error is specific enough to show verbatim.
                    messageRes = R.string.transfer_failed_msg,
                    messageText = errorMessage?.takeIf {
                        it.isNotEmpty() && it.contains(sendToWalletError)
                    },
                    showContactSupport = true,
                    buttonTextRes = R.string.button_retry
                )
                TransactionType.BuySwap,
                TransactionType.TransferDash -> ResultSpec(
                    resultType = MayaResultType.TRANSFER_DASH_ERROR,
                    isSuccess = false,
                    titleRes = R.string.transfer_failed,
                    messageRes = R.string.transfer_dash_failed_msg,
                    messageText = errorMessage?.takeIf { it.isNotEmpty() },
                    showContactSupport = true,
                    buttonTextRes = R.string.button_retry
                )
                TransactionType.SellSwap -> ResultSpec(
                    resultType = MayaResultType.SWAP_ERROR,
                    isSuccess = false,
                    titleRes = R.string.conversion_failed,
                    messageRes = R.string.transfer_failed_msg,
                    messageText = errorMessage?.takeIf { it.isNotEmpty() },
                    showContactSupport = true,
                    buttonTextRes = R.string.button_retry
                )
            }
        }
    }

    /**
     * Classifies the settlement route and picks the explorer link for a successful swap.
     * Maya tracks a swap by its inbound (DASH) transaction hash — MAYAChain indexes them by
     * uppercase hash. NEAR Intents tracks a swap by the one-time deposit address it issued.
     * A missing identifier falls back to the explorer's home page; an empty route name means
     * Maya; unrecognised routes show nothing.
     */
    fun explorerFor(routeName: String?, txid: String?, depositAddress: String?): ExplorerSpec? {
        val raw = routeName?.trim().orEmpty()
        val isMayaRoute = raw.isEmpty() || raw.contains("MAYA", ignoreCase = true)
        val isNearRoute = !isMayaRoute && raw.contains("NEAR", ignoreCase = true)

        return when {
            isMayaRoute -> ExplorerSpec(
                linkTextRes = R.string.maya_explorer_view_maya,
                urlRes = if (txid.isNullOrBlank()) {
                    R.string.maya_explorer_url_maya
                } else {
                    R.string.maya_explorer_tx_url_maya
                },
                urlArg = txid?.takeIf { it.isNotBlank() }?.uppercase()
            )
            isNearRoute -> ExplorerSpec(
                linkTextRes = R.string.maya_explorer_view_near,
                urlRes = if (depositAddress.isNullOrBlank()) {
                    R.string.maya_explorer_url_near
                } else {
                    R.string.maya_explorer_tx_url_near
                },
                urlArg = depositAddress?.takeIf { it.isNotBlank() }
            )
            else -> null
        }
    }
}
