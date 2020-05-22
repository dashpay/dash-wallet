package de.schildbach.wallet.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
//import com.bumptech.glide.Glide
import de.schildbach.wallet_test.R
import org.dashevo.dpp.document.Document

class DashPayProfilesAdapter() : RecyclerView.Adapter<DashPayProfilesAdapter.ViewHolder>() {

    var profiles: List<Document> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context), parent)
    }

    override fun getItemCount(): Int {
        return profiles.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(profiles[position])
    }

    inner class ViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.dashpay_profile_row, parent, false)) {

        private val avatar by lazy { itemView.findViewById<ImageView>(R.id.avatar) }
        private val publicMessage by lazy { itemView.findViewById<TextView>(R.id.publicMessage) }
        private val displayName by lazy { itemView.findViewById<TextView>(R.id.displayName) }

        fun bind(document: Document) {
            displayName.text = document.data["displayName"].toString()
            publicMessage.text = document.data["publicMessage"].toString()
            /*
            Glide.with(avatar).load(document.data["avatarUrl"]).circleCrop()
                    .placeholder(R.drawable.user5).into(avatar)
             */
        }

    }
}
