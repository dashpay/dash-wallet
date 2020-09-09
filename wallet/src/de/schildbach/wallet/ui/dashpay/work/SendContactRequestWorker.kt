package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.work.CoroutineWorker
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

        const val KEY_PROGRESS = "SendContactRequestWorker.KEY_PROGRESS"
    }

    private val platformRepo = PlatformRepo.getInstance()

    override suspend fun doWork(): Result {
        println("doWork#4")
        val encryptionKeyBytes = inputData.getByteArray(DeriveKeyWorker.KEY_ENCRYPTION_KEY)
                ?: return Result.failure()
        println("doWork#5")
        val encryptionKey = KeyParameter(encryptionKeyBytes)
        println("doWork#6")
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()

        println("doWork#7")
        val sendContactRequestResult = platformRepo.sendContactRequest(userId, encryptionKey, this)
        println("doWork#8")
        return when (sendContactRequestResult.status) {
            Status.SUCCESS -> Result.success()
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