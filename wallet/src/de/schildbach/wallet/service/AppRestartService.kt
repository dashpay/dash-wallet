package de.schildbach.wallet.service

import android.app.Activity
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.ui.OnboardingActivity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.inject.Inject

interface RestartService {

    /**
     * Perform restart
     *
     * @param activity the current activity derived from [Activity]
     * @param finishAffinity if true, the current activity and its backstack is finished
     */
    fun performRestart(activity: Activity, finishAffinity: Boolean, upgrade: Boolean = false)

    /**
     * Perform restart
     *
     * @param activity the current activity derived from [FragmentActivity]
     * @param finishAffinity if true, the current activity and its backstack is finished
     */
    fun performRestart(activity: FragmentActivity, finishAffinity: Boolean, upgrade: Boolean = false)
}

class AppRestartService @Inject constructor() : RestartService {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(AppRestartService::class.java)
    }

    override fun performRestart(activity: Activity, finishAffinity: Boolean, upgrade: Boolean) {
        log.info("perform restart on ${activity.javaClass.simpleName}: $finishAffinity")
        activity.startActivity(OnboardingActivity.createIntent(activity, upgrade))
        if (finishAffinity) {
            activity.finishAffinity()
        }
    }

    override fun performRestart(activity: FragmentActivity, finishAffinity: Boolean, upgrade: Boolean) {
        log.info("perform restart on ${activity.javaClass.simpleName}: $finishAffinity")
        activity.startActivity(OnboardingActivity.createIntent(activity, upgrade))
        if (finishAffinity) {
            activity.finishAffinity()
        }
    }
}