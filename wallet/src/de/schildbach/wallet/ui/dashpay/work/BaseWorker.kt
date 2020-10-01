package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory

abstract class BaseWorker(context: Context, parameters: WorkerParameters)
    : CoroutineWorker(context, parameters) {

    companion object {
        private val log = LoggerFactory.getLogger(BaseWorker::class.java)

        const val KEY_PROGRESS = "BaseWorker.KEY_PROGRESS"
        const val KEY_ERROR_MESSAGE = "BaseWorker.KEY_ERROR_MESSAGE"

        fun extractError(date: Data): String? {
            return date.getString(KEY_ERROR_MESSAGE)
        }
    }

    suspend fun setProgress(progress: Int) {
        setProgress(workDataOf(KEY_PROGRESS to progress))
    }

    protected fun formatExceptionMessage(description: String, e: Exception): String {
        var msg = if (e.localizedMessage != null) {
            e.localizedMessage
        } else {
            e.message
        }
        if (msg == null) {
            msg = "Unknown error - ${e.javaClass.simpleName}"
        }
        log.error("$description: $msg")
        if (e is StatusRuntimeException) {
            log.error("---> ${e.trailers}")
        }
        log.error(msg)
        e.printStackTrace()
        return msg
    }
}