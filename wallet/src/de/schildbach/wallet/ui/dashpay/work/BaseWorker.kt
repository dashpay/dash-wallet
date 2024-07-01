package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

abstract class BaseWorker(context: Context, parameters: WorkerParameters)
    : CoroutineWorker(context, parameters) {

    companion object {
        private val log = LoggerFactory.getLogger(BaseWorker::class.java)

        const val KEY_PROGRESS = "BaseWorker.PROGRESS"
        const val KEY_ERROR_MESSAGE = "BaseWorker.ERROR_MESSAGE"

        fun extractError(date: Data): String? {
            return date.getString(KEY_ERROR_MESSAGE)
        }

        fun extractProgress(date: Data): Int {
            return date.getInt(KEY_PROGRESS, -1)
        }
    }

    suspend fun setProgress(progress: Int) {
        setProgress(workDataOf(KEY_PROGRESS to progress))
    }

    override suspend fun doWork(): Result {
        setProgress(0)
        val result = doWorkWithBaseProgress()
        if (result is Result.Success) {
            setProgress(100)
            // there seems to be a bug in WorkManager, without that delay,
            // the SUCCESS status overrides the progress state
            // tested also with an official code snippet
            // https://developer.android.com/topic/libraries/architecture/workmanager/how-to/intermediate-progress#updating_progress
            delay(100)
        }
        return result
    }

    abstract suspend fun doWorkWithBaseProgress(): Result

    protected fun formatExceptionMessage(description: String, e: Exception): String {
        var msg = if (e.localizedMessage != null) {
            e.localizedMessage
        } else {
            e.message
        }
        if (msg == null) {
            msg = "Unknown error - ${e.javaClass.simpleName}"
        }
        log.error("$description: $msg", e)
        return msg
    }
}