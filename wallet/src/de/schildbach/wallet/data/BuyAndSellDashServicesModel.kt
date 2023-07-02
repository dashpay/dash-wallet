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

enum class ServiceStatus {
    CONNECTED, DISCONNECTED, IDLE, IDLE_DISCONNECTED
}

enum class ServiceType(
    @StringRes val serviceName: Int,
    @DrawableRes val serviceIcon: Int,
    @DrawableRes val offlineServiceIcon: Int
) {
    TOPPER(R.string.topper, R.drawable.logo_topper, R.drawable.logo_topper),
    UPHOLD(R.string.uphold_account, R.drawable.ic_uphold, R.drawable.ic_uphold_saturated),
    COINBASE(R.string.coinbase, R.drawable.ic_coinbase, R.drawable.ic_coinbase_saturated)
}

@Parcelize
data class BuyAndSellDashServicesModel(
    val serviceType: ServiceType,
    val serviceStatus: ServiceStatus,
    val balance: Coin? = null,
    val localBalance: Fiat? = null
): Parcelable {
    companion object {
        fun getBuyAndSellDashServicesList() = listOf(
            BuyAndSellDashServicesModel(ServiceType.TOPPER, ServiceStatus.IDLE),
            BuyAndSellDashServicesModel(ServiceType.UPHOLD, ServiceStatus.IDLE),
            BuyAndSellDashServicesModel(ServiceType.COINBASE, ServiceStatus.IDLE)
        )
    }

    fun isAvailable(): Boolean {
        return serviceStatus != ServiceStatus.DISCONNECTED && serviceStatus != ServiceStatus.IDLE_DISCONNECTED
    }
}
