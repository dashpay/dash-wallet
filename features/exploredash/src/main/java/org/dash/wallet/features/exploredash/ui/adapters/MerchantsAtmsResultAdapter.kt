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

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.explore.model.*
import org.dash.wallet.features.exploredash.databinding.AtmRowBinding
import org.dash.wallet.features.exploredash.databinding.MerchantRowBinding
import org.dash.wallet.features.exploredash.ui.extensions.isMetric
import org.slf4j.LoggerFactory
import java.util.*

open class ExploreViewHolder(root: View) : RecyclerView.ViewHolder(root) {
    fun getDistanceText(resources: Resources, item: SearchResult?): String {
        val isMetric = Locale.getDefault().isMetric
        val distanceStr = item?.getDistanceStr(isMetric)

        return when {
            distanceStr.isNullOrEmpty() -> ""
            isMetric -> resources.getString(R.string.distance_kilometers, distanceStr)
            else -> resources.getString(R.string.distance_miles, distanceStr)
        }
    }
}

class SearchResultDiffCallback<T : SearchResult> : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: T, newItem: T): Boolean {
        return oldItem == newItem
    }
}

class MerchantsAtmsResultAdapter(private val clickListener: (SearchResult, RecyclerView.ViewHolder) -> Unit) :
    PagingDataAdapter<SearchResult, RecyclerView.ViewHolder>(SearchResultDiffCallback()) {

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
}

class MerchantViewHolder(val binding: MerchantRowBinding) : ExploreViewHolder(binding.root) {
    companion object {
        private val log = LoggerFactory.getLogger(MerchantViewHolder::class.java)
    }
    fun bind(merchant: Merchant?) {
        val resources = binding.root.resources
        binding.title.text = merchant?.name
        binding.subtitle.text = getDistanceText(resources, merchant)
        binding.subtitle.isVisible = merchant?.type != MerchantType.ONLINE && binding.subtitle.text.isNotEmpty()
        if (merchant != null) {
            if (merchant.savingsFraction != 0.00) {
                binding.discountValue.isVisible = true
                binding.discountValue.text = binding.root.context.getString(
                    R.string.explore_discount,
                    merchant.savingsPercentageAsDouble
                )
            } else {
                binding.discountValue.isVisible = false
            }
        }

        binding.logoImg.load(merchant?.logoLocation) {
            crossfade(200)
            scale(Scale.FILL)
            placeholder(R.drawable.ic_image_placeholder)
            error(R.drawable.ic_image_placeholder)
            transformations(
                RoundedCornersTransformation(resources.getDimensionPixelSize(R.dimen.logo_corners_radius).toFloat())
            )
            listener(
                onError = { _, result ->
                    log.error(
                        "Image load error for ${
                        merchant?.name
                        }: ${merchant?.logoLocation}: ${result.throwable.message}",
                        result.throwable
                    )
                }
            )
        }

        when (merchant?.paymentMethod?.trim()?.lowercase()) {
            PaymentMethod.DASH -> binding.methodImg.setImageResource(R.drawable.ic_dash_pay)
            PaymentMethod.GIFT_CARD -> binding.methodImg.setImageResource(R.drawable.ic_gift_card_rounded)
        }
    }
}

class AtmViewHolder(val binding: AtmRowBinding) : ExploreViewHolder(binding.root) {
    fun bind(atm: Atm?) {
        val resources = binding.root.resources
        binding.title.text = atm?.name
        val manufacturer = atm?.manufacturer?.replaceFirstChar { it.titlecase() } ?: ""
        val distanceText = getDistanceText(resources, atm)
        binding.subtitle.text =
            if (distanceText.isEmpty()) {
                manufacturer
            } else {
                "$distanceText • $manufacturer"
            }
        binding.subtitle.isVisible = binding.subtitle.text.isNotEmpty()

        binding.logoImg.load(atm?.logoLocation) {
            crossfade(200)
            scale(Scale.FILL)
            placeholder(R.drawable.ic_image_placeholder)
            error(R.drawable.ic_image_placeholder)
            transformations(
                RoundedCornersTransformation(resources.getDimensionPixelSize(R.dimen.logo_corners_radius).toFloat())
            )
        }

        binding.buyIcon.isVisible = atm?.type != AtmType.SELL
        binding.sellIcon.isVisible = atm?.type != AtmType.BUY && !atm?.type.isNullOrEmpty()
    }
}
