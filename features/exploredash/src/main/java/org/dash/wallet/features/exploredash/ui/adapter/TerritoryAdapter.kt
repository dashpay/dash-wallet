package org.dash.wallet.features.exploredash.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.features.exploredash.databinding.TerritoryRowBinding

class TerritoryAdapter(private val clickListener: (String, TerritoryViewHolder) -> Unit)
    : ListAdapter<String, TerritoryViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TerritoryViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        val binding = TerritoryRowBinding.inflate(inflater, parent, false)
        return TerritoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TerritoryViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.binding.root.setOnClickListener { clickListener.invoke(item, holder) }
    }

    class DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}

class TerritoryViewHolder(val binding: TerritoryRowBinding) : RecyclerView.ViewHolder(binding.root) {
    fun bind(title: String?) {
        binding.title.text = title
    }
}