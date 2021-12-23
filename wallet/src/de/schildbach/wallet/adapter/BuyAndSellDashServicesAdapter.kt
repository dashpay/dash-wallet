package de.schildbach.wallet.adapter


import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import de.schildbach.wallet_test.databinding.ItemServiceListBinding
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.Constants
import org.dash.wallet.common.ui.BaseAdapter
import org.dash.wallet.integration.liquid.R
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*

class BuyAndSellDashServicesAdapter( val config: Configuration,val onClickListener:ClickListener) : BaseAdapter<BuyAndSellDashServicesModel>(){

    override fun viewHolder(layout: Int, viewGroup: ViewGroup): BaseViewHolder {
        return BuyAndSellDashServicesViewHolder(
            ItemServiceListBinding.inflate(LayoutInflater.from(viewGroup.context), viewGroup, false)
        )

    }

    inner class BuyAndSellDashServicesViewHolder( val binding: ItemServiceListBinding) : BaseViewHolder(binding.root) {
        @SuppressLint("SetTextI18n")
        override fun bindData(data: BuyAndSellDashServicesModel?) {
            data?.let {
                binding.root.setOnClickListener {
                    onClickListener.onItemClick(adapterPosition,view,data)
                }
                binding.serviceImg.setImageDrawable(ContextCompat.getDrawable(view.context,  it.serviceType.serviceIcon))
                binding.serviceName.text =view.context.getString(it.serviceType.serviceName)
                when (it.serviceStatus) {
                    BuyAndSellDashServicesModel.ServiceStatus.IDLE ->setIdleView()
                    BuyAndSellDashServicesModel.ServiceStatus.CONNECTED ->setConnectedView()
                    BuyAndSellDashServicesModel.ServiceStatus.DISCONNECTED ->setDisconnectedView()
                }
                binding.serviceBalance.setFormat(config.format.noCode())
                binding.serviceBalance.setApplyMarkup(false)
                binding.serviceBalance.setAmount(Coin.ZERO)

                val currencySymbol = (NumberFormat.getCurrencyInstance() as DecimalFormat).apply {
                    currency = Currency.getInstance(config.exchangeCurrencyCode)
                }.decimalFormatSymbols.currencySymbol

                val fiatFormat = MonetaryFormat().noCode().minDecimals(2).optionalDecimals()

                binding.serviceFiatAmount.text = Constants.PREFIX_ALMOST_EQUAL_TO+ view.context.getString(
                    R.string.fiat_balance_with_currency, fiatFormat.format(Coin.ZERO),
                    currencySymbol
                )

                if(data.balance!=null){
                    binding.serviceBalance.setAmount(data.balance)
                }
                if(data.localBalance!=null){
                    binding.serviceFiatAmount.text = Constants.PREFIX_ALMOST_EQUAL_TO+  view.context.getString(
                        R.string.fiat_balance_with_currency, fiatFormat.format(data.localBalance),
                        currencySymbol
                    )
                }

            }

        }

        private fun setIdleView(){
            binding.coinbaseStatusGroup.isGone =true
            binding.connected.isGone =true
            binding.disconnected.isGone =true
            binding.lastKnownBalance.isGone =true
        }
        private fun setConnectedView(){
            binding.coinbaseStatusGroup.isVisible =true
            binding.connected.isVisible =true
            binding.disconnected.isGone =true
            binding.lastKnownBalance.isGone =true
        }

        private fun setDisconnectedView(){
            binding.coinbaseStatusGroup.isVisible =true
            binding.connected.isGone =true
            binding.disconnected.isVisible =true
            binding.lastKnownBalance.isVisible =true
        }
    }
    interface ClickListener {
        fun onItemClick(position: Int, v: View,data: BuyAndSellDashServicesModel)
    }
}