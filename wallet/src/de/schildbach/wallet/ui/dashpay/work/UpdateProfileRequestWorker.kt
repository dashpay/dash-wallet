package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.delay
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dpp.document.Document
import org.json.JSONObject

class UpdateProfileRequestWorker(context: Context, parameters: WorkerParameters)
    : BaseWorker(context, parameters) {

    companion object {
        const val KEY_PASSWORD = "UpdateProfileRequestWorker.PASSWORD"
        const val KEY_PROFILE = "UpdateProfileRequestWorker.PROFILE"
        const val KEY_DISPLAY_NAME = "UpdateProfileRequestWorker.DISPLAY_NAME"
        const val KEY_PUBLIC_MESSAGE = "UpdateProfileRequestWorker.PUBLIC_MESSAGE"
        const val KEY_AVATAR_URL = "UpdateProfileRequestWorker.AVATAR_URL"
        const val KEY_USER_ID = "UpdateProfileRequestWorker.KEY_USER_ID"
        const val KEY_CREATED_AT = "UpdateProfileRequestWorker.CREATED_AT"

        fun extractUserId(date: Data): String? {
            return date.getString(KEY_USER_ID)
        }

        /*fun extractDisplayName(data: Data): String? {
            return data.getString(KEY_DISPLAY_NAME)
        }

        fun extractPublicMessage(data: Data): String? {
            return data.getString(KEY_PUBLIC_MESSAGE)
        }

        fun extractAvatarUrl(data: Data): String? {
            return data.getString(KEY_AVATAR_URL)
        }*/
    }

    private val platformRepo = PlatformRepo.getInstance()

    override suspend fun doWork(): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        //val dashPayProfileString = inputData.getString(KEY_PROFILE)
        //        ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PROFILE parameter"))
        //val rawProfile = JSONObject(dashPayProfileString).toMap()
        val displayName = inputData.getString(KEY_DISPLAY_NAME)?:""
        val publicMessage = inputData.getString(KEY_PUBLIC_MESSAGE)?:""
        val avatarUrl = inputData.getString(KEY_AVATAR_URL)?:""
        if (!inputData.keyValueMap.containsKey(KEY_CREATED_AT))
                return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_CREATED_AT parameter"))
        val createdAt = inputData.getLong(KEY_CREATED_AT, 0L)
        val blockchainIdentity = platformRepo.getBlockchainIdentity()!!
        val dashPayProfile = DashPayProfile(blockchainIdentity.uniqueIdString,
                blockchainIdentity.getUniqueUsername(),
                displayName,
                publicMessage,
                avatarUrl,
                createdAt
        )

        val encryptionKey: KeyParameter
        try {
            encryptionKey = WalletApplication.getInstance().wallet!!.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        return try {
            val profileRequestResult = platformRepo.broadcastUpdatedProfile(dashPayProfile!!, encryptionKey)
            Result.success(workDataOf(
                    KEY_USER_ID to profileRequestResult.userId
            ))
        } catch (ex: Exception) {
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("send contact request", ex)))
        }
    }
}