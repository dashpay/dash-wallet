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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import de.schildbach.wallet.data.ServiceStatus
import de.schildbach.wallet_test.databinding.ItemServiceListBinding
import org.bitcoinj.core.Coin
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import androidx.recyclerview.widget.ListAdapter
import de.schildbach.wallet_test.R
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.util.toFormattedString


class BuyAndSellDashServicesAdapter(
    val balanceFormat: MonetaryFormat,
    val onClickListener: (BuyAndSellDashServicesModel) -> Unit
) : ListAdapter<BuyAndSellDashServicesModel, BuyAndSellDashServicesAdapter.BuyAndSellDashServicesViewHolder>(
    BuyAndSellDashDiffCallback()
) {
    class BuyAndSellDashDiffCallback : DiffUtil.ItemCallback<BuyAndSellDashServicesModel>() {
        override fun areItemsTheSame(oldItem: BuyAndSellDashServicesModel, newItem: BuyAndSellDashServicesModel): Boolean {
            return oldItem.serviceType == oldItem.serviceType
        }

        override fun areContentsTheSame(oldItem: BuyAndSellDashServicesModel, newItem: BuyAndSellDashServicesModel): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): BuyAndSellDashServicesViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemServiceListBinding.inflate(inflater, parent, false)

        return BuyAndSellDashServicesViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BuyAndSellDashServicesViewHolder, position: Int) {
        val item = getItem(position)
        holder.bindData(item)
    }

    inner class BuyAndSellDashServicesViewHolder(val binding: ItemServiceListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindData(data: BuyAndSellDashServicesModel?) {
            data?.let { service ->
                binding.root.background = ContextCompat.getDrawable(binding.root.context,
                    if (service.isAvailable()) {
                        R.drawable.rounded_ripple_background
                    } else {
                        R.drawable.rounded_background
                    })
                binding.root.setOnClickListener {
                    if (service.isAvailable()) {
                        onClickListener.invoke(data)
                    }
                }
                binding.serviceImg.setImageDrawable(ContextCompat.getDrawable(binding.root.context,
                    if(service.isAvailable()) {
                        service.serviceType.serviceIcon
                    } else {
                        service.serviceType.offlineServiceIcon
                    }))
                binding.serviceName.text = binding.root.context.getString(service.serviceType.serviceName)

                when (service.serviceStatus) {
                    ServiceStatus.IDLE -> setIdleView()
                    ServiceStatus.IDLE_DISCONNECTED -> setIdleDisconnectedView()
                    ServiceStatus.CONNECTED -> setConnectedView()
                    ServiceStatus.DISCONNECTED -> setDisconnectedView()
                }
                binding.serviceBalance.setFormat(balanceFormat)
                binding.serviceBalance.setApplyMarkup(false)
                binding.serviceBalance.setAmount(Coin.ZERO)

                if(data.balance != null) {
                    binding.serviceBalance.setAmount(data.balance)
                }

                if(data.localBalance != null) {
                    binding.serviceFiatAmount.text = "${Constants.PREFIX_ALMOST_EQUAL_TO} ${data.localBalance.toFormattedString()}"
                }
            }
        }

        private fun setIdleView() {
            binding.coinbaseStatusGroup.isGone = true
            binding.connected.isGone = true
            binding.disconnected.isGone = true
            binding.lastKnownBalance.isGone = true
            binding.imgArrow.isVisible = true
        }

        private fun setIdleDisconnectedView() {
            binding.coinbaseStatusGroup.isVisible = false
            binding.connected.isVisible = false
            binding.disconnected.isVisible = false
            binding.lastKnownBalance.isVisible = false
            binding.imgArrow.isVisible = false
        }

        private fun setConnectedView() {
            binding.coinbaseStatusGroup.isVisible = true
            binding.connected.isVisible = true
            binding.disconnected.isGone = true
            binding.lastKnownBalance.isGone = true
            binding.imgArrow.isVisible = true
        }

        private fun setDisconnectedView() {
            binding.coinbaseStatusGroup.isVisible = true
            binding.connected.isGone = true
            binding.disconnected.isVisible = true
            binding.lastKnownBalance.isVisible = true
            binding.imgArrow.isVisible = false
        }
    }
}

