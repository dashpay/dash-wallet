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

package de.schildbach.wallet.adapter


import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import de.schildbach.wallet_test.databinding.ItemServiceListBinding
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.Constants
import org.dash.wallet.common.ui.BaseAdapter
import org.dash.wallet.common.util.GenericUtils

class BuyAndSellDashServicesAdapter( val config: Configuration,
                                     val onClickListener: (BuyAndSellDashServicesModel) -> Unit) : BaseAdapter<BuyAndSellDashServicesModel>(){
    var isOnline: Boolean = false
    override fun viewHolder(layout: Int, view: ViewGroup): BaseViewHolder {
        return BuyAndSellDashServicesViewHolder(
            ItemServiceListBinding.inflate(LayoutInflater.from(view.context), view, false)
        )

    }

    fun updateIconState(hasInternet: Boolean) {
        isOnline = hasInternet
        notifyDataSetChanged()
    }

    inner class BuyAndSellDashServicesViewHolder( val binding: ItemServiceListBinding) : BaseViewHolder(binding.root) {
        @SuppressLint("SetTextI18n")
        override fun bindData(data: BuyAndSellDashServicesModel?) {
            data?.let {
                binding.root.setOnClickListener { if (isOnline) onClickListener.invoke(data) }
                binding.serviceImg.setImageDrawable(ContextCompat.getDrawable(view.context,
                    if(isOnline) it.serviceType.serviceIcon else it.serviceType.getOfflineServiceIcon()))
                binding.serviceName.text =view.context.getString(it.serviceType.serviceName)
                when (it.serviceStatus) {
                    BuyAndSellDashServicesModel.ServiceStatus.IDLE ->setIdleView()
                    BuyAndSellDashServicesModel.ServiceStatus.CONNECTED ->setConnectedView()
                    BuyAndSellDashServicesModel.ServiceStatus.DISCONNECTED ->setDisconnectedView()
                }
                binding.serviceBalance.setFormat(config.format.noCode())
                binding.serviceBalance.setApplyMarkup(false)
                binding.serviceBalance.setAmount(Coin.ZERO)

                if(data.balance!=null){
                    binding.serviceBalance.setAmount(data.balance)
                }
                if(data.localBalance!=null){
                    binding.serviceFiatAmount.text ="${Constants.PREFIX_ALMOST_EQUAL_TO} ${GenericUtils.fiatToString(data.localBalance)}"
                }
                binding.imgArrow.isVisible = isOnline
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
}