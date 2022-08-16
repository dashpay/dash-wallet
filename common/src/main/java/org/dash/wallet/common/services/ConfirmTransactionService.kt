package org.dash.wallet.common.services

import androidx.fragment.app.FragmentActivity

interface ConfirmTransactionService {
    suspend fun showTransactionDetailsPreview(
        activity: FragmentActivity,
        address: String, amount: String, amountFiat: String, fiatSymbol: String, fee: String, total: String,
        payeeName: String? = null, payeeVerifiedBy: String? = null, buttonText: String? = null
    ): Boolean
}