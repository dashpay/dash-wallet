package de.schildbach.wallet.data

import android.view.View

interface OnContactItemClickListener {
    fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult)
}