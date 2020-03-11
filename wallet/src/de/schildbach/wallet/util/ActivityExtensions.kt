package de.schildbach.wallet.util

import android.app.Activity
import android.widget.Toast
import de.schildbach.wallet_test.R

fun Activity.showBlockchainSyncingMessage() {
    Toast.makeText(this, R.string.send_coins_fragment_hint_replaying,
            Toast.LENGTH_SHORT).show()
}
