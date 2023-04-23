package de.schildbach.wallet.ui.dashpay.work

import android.annotation.SuppressLint
import android.app.Application
import androidx.work.*
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.security.SecurityGuard

class UpdateProfileOperation(val application: Application) {

    companion object {
        const val WORK_NAME = "UpdateProfile.WORK"
    }

    @SuppressLint("EnqueueWork")
    fun create(dashPayProfile: DashPayProfile, uploadService: String, localAvatarUrl: String): WorkContinuation {

        val password = SecurityGuard().retrievePassword()
        val updateProfileWorker = OneTimeWorkRequestBuilder<UpdateProfileWorker>()
                .setInputData(workDataOf(
                        UpdateProfileWorker.KEY_PASSWORD to password,
                        UpdateProfileWorker.KEY_DISPLAY_NAME to dashPayProfile.displayName,
                        UpdateProfileWorker.KEY_PUBLIC_MESSAGE to dashPayProfile.publicMessage,
                        UpdateProfileWorker.KEY_AVATAR_URL to dashPayProfile.avatarUrl,
                        UpdateProfileWorker.KEY_AVATAR_HASH to dashPayProfile.avatarHash,
                        UpdateProfileWorker.KEY_AVATAR_FINGERPRINT to dashPayProfile.avatarFingerprint,
                        UpdateProfileWorker.KEY_CREATED_AT to dashPayProfile.createdAt,
                        UpdateProfileWorker.KEY_UPLOAD_SERVICE to uploadService,
                        UpdateProfileWorker.KEY_LOCAL_AVATAR_URL_TO_UPLOAD to localAvatarUrl))
                .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        updateProfileWorker)
    }

}