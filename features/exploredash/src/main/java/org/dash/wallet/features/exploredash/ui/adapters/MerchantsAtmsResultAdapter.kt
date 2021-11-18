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

package org.dash.wallet.features.exploredash.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.*
import org.dash.wallet.features.exploredash.databinding.AtmRowBinding
import org.dash.wallet.features.exploredash.databinding.MerchantRowBinding

class MerchantsAtmsResultAdapter(
    private val clickListener: (SearchResult, RecyclerView.ViewHolder) -> Unit
) : PagingDataAdapter<SearchResult, RecyclerView.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        if (position >= itemCount) {
            return -1
        }

        return when (getItem(position)) {
            is Merchant -> R.layout.merchant_row
            is Atm -> R.layout.atm_row
            else -> -1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            R.layout.merchant_row -> {
                val binding = MerchantRowBinding.inflate(inflater, parent, false)
                MerchantViewHolder(binding)
            }
            R.layout.atm_row -> {
                val binding = AtmRowBinding.inflate(inflater, parent, false)
                AtmViewHolder(binding)
            }
            else -> {
                throw IllegalArgumentException("viewType $viewType isn't recognized")
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)

        when (holder) {
            is MerchantViewHolder -> {
                holder.bind(item as Merchant)
                holder.binding.root.setOnClickListener { clickListener.invoke(item, holder) }
            }
            is AtmViewHolder -> {
                holder.bind(item as Atm)
                holder.binding.root.setOnClickListener { clickListener.invoke(item, holder) }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
            return oldItem == newItem
        }
    }
}

class MerchantViewHolder(val binding: MerchantRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(merchant: Merchant?) {
        val resources = binding.root.resources
        binding.title.text = merchant?.name

        Glide.with(binding.root.context)
            .load(merchant?.logoLocation)
            .error(R.drawable.ic_image_placeholder)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .transform(RoundedCorners(resources.getDimensionPixelSize(R.dimen.logo_corners_radius)))
            .into(binding.logoImg)

        when(merchant?.paymentMethod?.trim()?.lowercase()) {
            PaymentMethod.DASH -> binding.methodImg.setImageResource(R.drawable.ic_dash_pay)
            PaymentMethod.GIFT_CARD -> binding.methodImg.setImageResource(R.drawable.ic_gift_card)
        }
    }
}

class AtmViewHolder(val binding: AtmRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(atm: Atm?) {
        val resources = binding.root.resources
        binding.title.text = atm?.name
        binding.subtitle.text = atm?.manufacturer?.replaceFirstChar { it.titlecase() }

        Glide.with(binding.root.context)
            .load(atm?.logoLocation)
            .error(R.drawable.ic_image_placeholder)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .transform(RoundedCorners(resources.getDimensionPixelSize(R.dimen.logo_corners_radius)))
            .into(binding.logoImg)

        binding.buyIcon.isVisible = atm?.type == AtmType.BOTH || atm?.type == AtmType.BUY
        binding.sellIcon.isVisible = atm?.type == AtmType.BOTH || atm?.type == AtmType.SELL
    }
}