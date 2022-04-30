package de.schildbach.wallet.ui.send

import androidx.fragment.app.FragmentActivity
import org.dash.wallet.common.services.ConfirmTransactionService
import javax.inject.Inject

class ConfirmTransactionLauncher @Inject constructor(): ConfirmTransactionService {
    override suspend fun showTransactionDetailsPreview(
        activity: FragmentActivity,
        address: String,
        amount: String,
        amountFiat: String,
        fiatSymbol: String,
        fee: String,
        total: String,
        payeeName: String?,
        payeeVerifiedBy: String?,
        buttonText: String?
    ): Boolean {
        return ConfirmTransactionDialog.showDialogAsync(activity, address, amount, amountFiat, fiatSymbol, fee, total)
    }
}