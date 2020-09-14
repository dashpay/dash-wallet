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
        val password = inputData.getString(KEY_PASSWORD) ?: return Result.failure()
        return try {
            val encryptionKey = WalletApplication.getInstance().wallet!!.keyCrypter!!.deriveKey(password)
            Result.success(workDataOf(KEY_ENCRYPTION_KEY to encryptionKey.key))
        } catch (ex: KeyCrypterException) {
            Result.failure()
        }
    }
}