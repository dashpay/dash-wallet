/*
 * Copyright 2021 Dash Core Group.
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

package de.schildbach.wallet.data

import android.os.Parcelable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.schildbach.wallet_test.R
import kotlinx.android.parcel.Parcelize
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat

@Parcelize
data class BuyAndSellDashServicesModel(
    val serviceType: ServiceType,
    var serviceStatus: ServiceStatus,
    var balance: Coin? = null,
    var localBalance: Fiat? = null
): Parcelable {
    enum class ServiceType(
        @StringRes val serviceName: Int,
        @DrawableRes val serviceIcon: Int
    ) {
        UPHOLD(org.dash.wallet.integration.uphold.R.string.uphold_account, R.drawable.ic_uphold) {
            override fun getOfflineServiceIcon() = R.drawable.ic_uphold_saturated
        },
        COINBASE(org.dash.wallet.integration.coinbase_integration.R.string.coinbase, R.drawable.ic_coinbase){
            override fun getOfflineServiceIcon() = R.drawable.ic_coinbase_saturated
        };

        abstract fun getOfflineServiceIcon(): Int
    }

    enum class ServiceStatus {
        CONNECTED, DISCONNECTED, IDLE
    }

    companion object {
        fun getBuyAndSellDashServicesList() = listOf(
            BuyAndSellDashServicesModel(ServiceType.UPHOLD, ServiceStatus.IDLE),
            BuyAndSellDashServicesModel(ServiceType.COINBASE, ServiceStatus.IDLE)
        )
    }
}
