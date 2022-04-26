package de.schildbach.wallet.service

import android.app.Activity
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.ui.OnboardingActivity

interface RestartService {
    fun performRestart(activity: Activity, finishAffinity: Boolean)
    fun performRestart(activity: FragmentActivity, finishAffinity: Boolean)
}

class AppRestartService : RestartService {
    override fun performRestart(activity: Activity, finishAffinity: Boolean) {
        OnboardingActivity.createIntent(activity)
        activity.finishAffinity()
    }

    override fun performRestart(activity: FragmentActivity, finishAffinity: Boolean) {
        activity.startActivity(OnboardingActivity.createIntent(activity))
        if (finishAffinity)
            activity.finishAffinity()
    }
}