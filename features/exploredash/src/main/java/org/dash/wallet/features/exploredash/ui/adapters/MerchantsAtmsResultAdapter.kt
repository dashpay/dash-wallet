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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.GroupHeaderBinding
import org.dash.wallet.features.exploredash.databinding.MerchantRowBinding
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.MerchantType
import org.dash.wallet.features.exploredash.data.model.PaymentMethod
import org.dash.wallet.features.exploredash.data.model.SearchResult

class MerchantsAtmsResultAdapter(private val clickListener: (SearchResult, MerchantsViewHolder) -> Unit)
    : ListAdapter<SearchResult, RecyclerView.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        if (position >= itemCount) {
            return -1
        }

        val item = getItem(position)
        return if (item is Merchant) R.layout.merchant_row else R.layout.group_header
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == R.layout.merchant_row) {
            val binding = MerchantRowBinding.inflate(inflater, parent, false)
            MerchantsViewHolder(binding)
        } else {
            val binding = GroupHeaderBinding.inflate(inflater, parent, false)
            GroupHeaderViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)

        if (holder is MerchantsViewHolder) {
            holder.bind(item as Merchant)
            holder.binding.root.setOnClickListener { clickListener.invoke(item, holder) }
        } else if (holder is GroupHeaderViewHolder) {
            holder.bind(item.name)
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

class GroupHeaderViewHolder(val binding: GroupHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(header: String?) {
        binding.header.text = if (header.isNullOrEmpty()) {
            binding.root.resources.getString(R.string.explore_online_merchant)
        } else {
            header
        }
    }
}

class MerchantsViewHolder(val binding: MerchantRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(merchant: Merchant?) {
        val resources = binding.root.resources
        binding.title.text = merchant?.name

        binding.subtitle.text = when (cleanValue(merchant?.type)) {
            MerchantType.ONLINE -> resources.getString(R.string.explore_online_merchant)
            MerchantType.PHYSICAL -> resources.getString(R.string.explore_physical_merchant)
            MerchantType.BOTH -> resources.getString(R.string.explore_both_types_merchant)
            else -> ""
        }

        Glide.with(binding.root.context)
            .load(merchant?.logoLocation)
            .error(R.drawable.ic_merchant_placeholder)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .transform(RoundedCorners(
                    resources.getDimensionPixelSize(R.dimen.logo_corners_radius)))
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