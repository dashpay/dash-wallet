/*
 * Copyright 2024 Dash Core Group.
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
package org.dash.wallet.integrations.maya.ui.convert_currency.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.TransactionType
import org.dash.wallet.integrations.maya.utils.MayaConstants

data class ServiceWallet(
    val cryptoWalletName: String,
    val cryptoWalletService: String,
    override var balance: String,
    val currency: String,
    override var faitAmount: String,
    val icon: String?
) : BaseServiceWallet(balance, faitAmount)

open class BaseServiceWallet(
    open var balance: String = MayaConstants.VALUE_ZERO,
    open var faitAmount: String = MayaConstants.VALUE_ZERO
)

@Parcelize
data class SendTransactionToWalletParams(
    val amount: Amount,
    val fees: Amount,
    val toAddress: String?,
    val type: String?,
    val description: String? = "Dash Wallet App",
    /** Hex hash of the broadcast DASH swap transaction; used to link the swap on a block explorer. */
    val txid: String? = null,
    /**
     * One-time deposit address the swap provider issued (the swap tx pays into it).
     * NEAR Intents' explorer looks a swap up by this address.
     */
    val depositAddress: String? = null
) : Parcelable

@Parcelize
data class MayaTransactionParams(
    val params: SendTransactionToWalletParams,
    val type: TransactionType,
    val coinbaseWalletName: String? = null,
    /** Raw SwapKit provider string (e.g. "MAYACHAIN", "NEAR") of the route the swap settled through. */
    val routeName: String? = null
) : Parcelable
