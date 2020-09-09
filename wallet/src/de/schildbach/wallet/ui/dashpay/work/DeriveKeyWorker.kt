package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.schildbach.wallet.WalletApplication
import org.bitcoinj.crypto.KeyCrypterException

class DeriveKeyWorker(context: Context, parameters: WorkerParameters)
    : CoroutineWorker(context, parameters) {

    companion object {
        const val KEY_PASSWORD = "DeriveKeyWorker.PASSWORD"
        const val KEY_ENCRYPTION_KEY = "DeriveKeyWorker.ENCRYPTION_KEY"
    }

    override suspend fun doWork(): Result {
        println("doWork#1")
        val password = inputData.getString(KEY_PASSWORD) ?: return Result.failure()
        return try {
            println("doWork#2")
            val encryptionKey = WalletApplication.getInstance().wallet!!.keyCrypter!!.deriveKey(password)
            println("doWork#3")
            Result.success(workDataOf(KEY_ENCRYPTION_KEY to encryptionKey.key))
        } catch (ex: KeyCrypterException) {
            println("doWork#error")
            Result.failure()
        }
    }
}