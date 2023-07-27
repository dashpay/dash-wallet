/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.payments

import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.ui.send.ConfirmTransactionDialog
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.services.ConfirmTransactionService
import javax.inject.Inject

class ConfirmTransactionLauncher @Inject constructor(): ConfirmTransactionService {
    override suspend fun showTransactionDetailsPreview(
        activity: FragmentActivity,
        address: String,
        amount: Coin,
        exchangeRate: ExchangeRate?,
        fee: String,
        total: String,
        payeeName: String?,
        payeeVerifiedBy: String?,
        buttonText: String?
    ): Boolean {
        return ConfirmTransactionDialog.showDialogAsync(
            activity,
            address,
            amount,
            exchangeRate,
            fee,
            total
        )
    }
}
