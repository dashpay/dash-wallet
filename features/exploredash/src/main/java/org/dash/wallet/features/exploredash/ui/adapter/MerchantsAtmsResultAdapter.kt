package org.dash.wallet.features.exploredash.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.MerchantRowBinding
import org.dash.wallet.features.exploredash.repository.model.Merchant
import org.dash.wallet.features.exploredash.repository.model.MerchantType
import org.dash.wallet.features.exploredash.repository.model.PaymentMethod
import java.util.*

class MerchantsAtmsResultAdapter(private val clickListener: (Long?, MerchantsViewHolder) -> Unit)
    : ListAdapter<Merchant, MerchantsViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MerchantsViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = MerchantRowBinding.inflate(inflater, parent, false)

        return MerchantsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MerchantsViewHolder, position: Int) {
        val merchant = getItem(position)
        holder.bind(merchant)
        holder.binding.root.setOnClickListener { clickListener.invoke(merchant.id, holder) }
    }

    class DiffCallback : DiffUtil.ItemCallback<Merchant>() {
        override fun areItemsTheSame(oldItem: Merchant, newItem: Merchant): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Merchant, newItem: Merchant): Boolean {
            return oldItem == newItem
        }
    }
}

class MerchantsViewHolder(val binding: MerchantRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(merchant: Merchant?) {
        val resources = binding.root.resources
        binding.title.text = merchant?.name ?: ""

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
            .into(binding.logoImg)

        when(cleanValue(merchant?.paymentMethod)) {
            PaymentMethod.DASH -> binding.methodImg.setImageResource(R.drawable.ic_dash_pay)
            PaymentMethod.GIFT_CARD -> binding.methodImg.setImageResource(R.drawable.ic_gift_card)
        }
    }

    private fun cleanValue(value: String?): String? {
        return value?.trim()?.toLowerCase(Locale.getDefault())?.replace(" ", "_")
    }
}