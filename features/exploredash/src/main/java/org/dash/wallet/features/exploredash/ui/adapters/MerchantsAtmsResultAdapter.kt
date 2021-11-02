/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

        when(cleanValue(merchant?.paymentMethod)) {
            PaymentMethod.DASH -> binding.methodImg.setImageResource(R.drawable.ic_dash_pay)
            PaymentMethod.GIFT_CARD -> binding.methodImg.setImageResource(R.drawable.ic_gift_card)
        }
    }

    private fun cleanValue(value: String?): String? {
        return value?.trim()?.lowercase()?.replace(" ", "_")
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