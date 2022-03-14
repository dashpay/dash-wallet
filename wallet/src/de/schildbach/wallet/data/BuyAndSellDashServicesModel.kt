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
        LIQUID(org.dash.wallet.integration.liquid.R.string.liquid, org.dash.wallet.common.R.drawable.ic_liquid){
            override fun getOfflineServiceIcon() = org.dash.wallet.common.R.drawable.ic_liquid_saturated
        },
        UPHOLD(org.dash.wallet.integration.uphold.R.string.uphold_account, org.dash.wallet.common.R.drawable.ic_uphold){
            override fun getOfflineServiceIcon() = org.dash.wallet.common.R.drawable.ic_uphold_saturated
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
            BuyAndSellDashServicesModel(ServiceType.LIQUID, ServiceStatus.IDLE),
            BuyAndSellDashServicesModel(ServiceType.UPHOLD, ServiceStatus.IDLE),
            BuyAndSellDashServicesModel(ServiceType.COINBASE, ServiceStatus.IDLE)
        )
    }
}
