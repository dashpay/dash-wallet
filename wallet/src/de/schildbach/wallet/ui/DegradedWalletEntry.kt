package de.schildbach.wallet.ui

import android.app.Activity
import android.content.Intent
import de.schildbach.wallet.WalletApplication

/** A recovered wallet can exist in memory without being safe to use yet. */
internal fun Activity.redirectDegradedWallet(application: WalletApplication): Boolean {
    if (!application.isWalletLoadDegraded) return false

    setResult(Activity.RESULT_CANCELED)
    startActivity(
        OnboardingActivity.createIntent(this).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
    )
    finish()
    return true
}
