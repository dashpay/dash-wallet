package de.schildbach.wallet.ui.dashpay.work

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.*
import de.schildbach.wallet.ui.security.SecurityGuard

class SendContactRequestOperation {

    companion object {
        const val WORK_NAME = "ContactRequest.WORK_NAME"
        const val TAG = "DeriveKeyWorker.TAG"
    }

    @SuppressLint("EnqueueWork")
    fun create(context: Context, userId: String): WorkContinuation {

        val password = SecurityGuard().retrievePassword()
        val deriveKeyWorker = OneTimeWorkRequestBuilder<DeriveKeyWorker>()
                .setInputData(workDataOf(DeriveKeyWorker.KEY_PASSWORD to password))
                .addTag(TAG)
                .build()

        val sendContactRequestWorker = OneTimeWorkRequestBuilder<SendContactRequestWorker>()
                .setInputData(workDataOf(SendContactRequestWorker.KEY_USER_ID to userId))
                .addTag(TAG)
                .build()

        return WorkManager.getInstance(context)
                .beginUniqueWork(WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        deriveKeyWorker)
                .then(sendContactRequestWorker)
    }
}