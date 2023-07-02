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
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import de.schildbach.wallet.data.ServiceStatus
import de.schildbach.wallet.data.ServiceType
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ItemServiceListBinding
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils

class BuyAndSellDashServicesAdapter(
    val balanceFormat: MonetaryFormat,
    val onClickListener: (BuyAndSellDashServicesModel) -> Unit
) : ListAdapter<BuyAndSellDashServicesModel, BuyAndSellDashServicesAdapter.BuyAndSellDashServicesViewHolder>(
    BuyAndSellDashDiffCallback()
) {
    class BuyAndSellDashDiffCallback : DiffUtil.ItemCallback<BuyAndSellDashServicesModel>() {
        override fun areItemsTheSame(
            oldItem: BuyAndSellDashServicesModel,
            newItem: BuyAndSellDashServicesModel
        ): Boolean {
            return oldItem.serviceType == newItem.serviceType
        }

        override fun areContentsTheSame(
            oldItem: BuyAndSellDashServicesModel,
            newItem: BuyAndSellDashServicesModel
        ): Boolean {
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

    inner class BuyAndSellDashServicesViewHolder(val binding: ItemServiceListBinding) : RecyclerView.ViewHolder(
        binding.root
    ) {
        @SuppressLint("SetTextI18n")
        fun bindData(data: BuyAndSellDashServicesModel?) {
            data?.let { service ->
                binding.root.background = ContextCompat.getDrawable(
                    binding.root.context,
                    if (service.isAvailable()) {
                        R.drawable.rounded_ripple_background
                    } else {
                        R.drawable.rounded_background
                    }
                )
                binding.root.setOnClickListener {
                    if (service.isAvailable()) {
                        onClickListener.invoke(data)
                    }
                }
                binding.serviceImg.setImageDrawable(
                    ContextCompat.getDrawable(
                        binding.root.context,
                        service.serviceType.serviceIcon
                    )
                )
                binding.serviceName.text = binding.root.context.getString(service.serviceType.serviceName)
                setStatus(service.serviceStatus)

                binding.serviceBalance.setFormat(balanceFormat)
                binding.serviceBalance.setApplyMarkup(false)
                binding.serviceBalance.setAmount(Coin.ZERO)

                if (data.balance != null) {
                    binding.serviceBalance.setAmount(data.balance)
                }

                if (data.localBalance != null) {
                    binding.serviceFiatAmount.text = "${Constants.PREFIX_ALMOST_EQUAL_TO} " +
                        GenericUtils.fiatToString(data.localBalance)
                }

                binding.additionalInfo.isVisible = data.serviceType == ServiceType.TOPPER
            }
        }

        private fun setStatus(status: ServiceStatus) {
            binding.coinbaseStatusGroup.isVisible = status == ServiceStatus.CONNECTED ||
                status == ServiceStatus.DISCONNECTED
            binding.connected.isVisible = status == ServiceStatus.CONNECTED
            binding.disconnected.isVisible = status == ServiceStatus.DISCONNECTED
            binding.lastKnownBalance.isVisible = status == ServiceStatus.DISCONNECTED
            binding.imgArrow.isVisible = status == ServiceStatus.IDLE || status == ServiceStatus.CONNECTED
            binding.serviceSubtitle.isVisible = status == ServiceStatus.IDLE ||
                status == ServiceStatus.IDLE_DISCONNECTED
        }
    }
}
