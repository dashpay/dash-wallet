package de.schildbach.wallet.service

import android.app.Activity
import android.content.Context
import android.content.Intent
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

    /**
     * Perform restart using a non-activity [Context] (e.g. the application context).
     *
     * Use this when the restart is triggered from an asynchronous callback that may outlive the
     * activity/fragment that started it (such as the wallet-wipe completion callback). Because there
     * is no activity to finish, the new task is started with [Intent.FLAG_ACTIVITY_NEW_TASK] and,
     * when [finishAffinity] is true, [Intent.FLAG_ACTIVITY_CLEAR_TASK] to clear any existing backstack.
     *
     * @param context any [Context]; the application context is recommended
     * @param finishAffinity if true, the existing task/backstack is cleared
     */
    fun performRestart(context: Context, finishAffinity: Boolean, upgrade: Boolean = false)
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

    override fun performRestart(context: Context, finishAffinity: Boolean, upgrade: Boolean) {
        log.info("perform restart on application context: $finishAffinity")
        val intent = OnboardingActivity.createIntent(context, upgrade).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (finishAffinity) {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }
        context.startActivity(intent)
    }
}