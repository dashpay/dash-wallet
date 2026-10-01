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

/**
 * What the degraded startup screen offers. Report and Close are always there;
 * these are the parts that depend on why the launch is degraded.
 */
internal data class DegradedScreenActions(
    val showWipeRecoveryMessage: Boolean,
    val offerSafeModeRetry: Boolean,
    val offerSeedRecovery: Boolean,
    /** Report is the main action, not a secondary one under another CTA. */
    val reportIsPrimary: Boolean,
    val autoShowReport: Boolean
)

/**
 * An unfinished or unverified wipe takes precedence over every other recovery
 * action. A wallet created, restored or loaded under its marker could be
 * deleted by the next launch, or could hide what is left of the old one, so
 * that screen leaves only report and close.
 */
internal fun degradedScreenActions(
    wipeRecoveryRequired: Boolean,
    safeMode: Boolean,
    recoveryFromSeedNeeded: Boolean,
    firstShow: Boolean
): DegradedScreenActions {
    if (wipeRecoveryRequired) {
        return DegradedScreenActions(
            showWipeRecoveryMessage = true,
            offerSafeModeRetry = false,
            offerSeedRecovery = false,
            reportIsPrimary = true,
            autoShowReport = false
        )
    }
    return DegradedScreenActions(
        showWipeRecoveryMessage = false,
        offerSafeModeRetry = safeMode && !recoveryFromSeedNeeded,
        offerSeedRecovery = recoveryFromSeedNeeded,
        reportIsPrimary = !recoveryFromSeedNeeded && !safeMode,
        autoShowReport = firstShow
    )
}
