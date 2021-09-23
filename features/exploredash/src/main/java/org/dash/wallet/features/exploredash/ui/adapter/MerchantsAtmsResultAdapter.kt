package org.dash.wallet.features.exploredash.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.features.exploredash.databinding.MerchantRowBinding
import org.dash.wallet.features.exploredash.repository.model.Merchant

class MerchantsAtmsResultAdapter(private val clickListener: (Long?, MerchantsViewHolder) -> Unit)
    : ListAdapter<Merchant, MerchantsViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MerchantsViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = MerchantRowBinding.inflate(inflater)

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
        binding.title.text = merchant?.name ?: "null"
        binding.subtitle.text = merchant?.address1 ?: "null"
    }
}