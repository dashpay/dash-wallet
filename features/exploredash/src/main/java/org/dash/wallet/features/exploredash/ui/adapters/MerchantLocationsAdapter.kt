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
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.databinding.MerchantLocationRowBinding

class MerchantsLocationsAdapter(private val clickListener: (Merchant, RecyclerView.ViewHolder) -> Unit) :
    ListAdapter<Merchant, MerchantLocationViewHolder>(SearchResultDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MerchantLocationViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = MerchantLocationRowBinding.inflate(inflater, parent, false)

        return MerchantLocationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MerchantLocationViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item as Merchant)
        holder.binding.clickableItem.setOnClickListener { clickListener.invoke(item, holder) }
    }
}

class MerchantLocationViewHolder(val binding: MerchantLocationRowBinding) : ExploreViewHolder(binding.root) {
    fun bind(merchant: Merchant?) {
        binding.locationName.text = merchant?.getDisplayAddress(", ")
        val distanceText = getDistanceText(binding.root.resources, merchant)
        binding.distance.text = distanceText
        binding.distance.isVisible = distanceText.isNotEmpty()
    }
}
