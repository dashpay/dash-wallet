package org.dash.wallet.common.ui

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

abstract class BaseAdapter<T : Any,>(
    diffCallback: DiffUtil.ItemCallback<T> = object : DiffUtil.ItemCallback<T>() {
        override fun areItemsTheSame(oldItem: T, newItem: T) =
            oldItem == newItem

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: T, newItem: T) =
            oldItem == newItem

        override fun getChangePayload(oldItem: T, newItem: T) = false
    }
) : ListAdapter<T, BaseAdapter<T>.BaseViewHolder>(diffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return viewHolder(viewType, parent)
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        holder.bindData(getItem(position))
    }
    protected abstract fun viewHolder(@LayoutRes layout: Int, view: ViewGroup): BaseViewHolder

    abstract inner class BaseViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bindData(data: T?)
    }
}
