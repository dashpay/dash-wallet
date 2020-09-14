package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.bouncycastle.crypto.params.KeyParameter

class SendContactRequestWorker(context: Context, parameters: WorkerParameters)
    : CoroutineWorker(context, parameters) {

    companion object {
        const val TAG = "SendContactRequestWorker.TAG"

        const val KEY_USER_ID = "SendContactRequestWorker.KEY_USER_ID"
        const val KEY_TO_USER_ID = "SendContactRequestWorker.KEY_TO_USER_ID"

        const val KEY_PROGRESS = "SendContactRequestWorker.KEY_PROGRESS"

        fun extractUserId(date: Data): String? {
            return date.getString(KEY_USER_ID)
        }

        fun extractToUserId(date: Data): String? {
            return date.getString(KEY_TO_USER_ID)
        }
    }

    private val platformRepo = PlatformRepo.getInstance()

    override suspend fun doWork(): Result {
        val encryptionKeyBytes = inputData.getByteArray(DeriveKeyWorker.KEY_ENCRYPTION_KEY)
                ?: return Result.failure()
        val encryptionKey = KeyParameter(encryptionKeyBytes)
        val userId = extractUserId(inputData) ?: return Result.failure()

        val sendContactRequestResult = platformRepo.sendContactRequest(userId, encryptionKey, this)
        return when (sendContactRequestResult.status) {
            Status.SUCCESS -> Result.success(workDataOf(
                    KEY_USER_ID to sendContactRequestResult.data!!.userId,
                    KEY_TO_USER_ID to sendContactRequestResult.data.toUserId
            ))
            else -> Result.failure()
        }
    }

    suspend fun setProgress(progress: Progress) {
        setProgress(workDataOf(KEY_PROGRESS to progress.name))
    }

    enum class Progress {
        INIT,
        SEND,
        DONE,
        ERROR
    }
}