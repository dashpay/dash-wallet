package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.squareup.moshi.Moshi
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.ImgurUploadResponse
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.security.SecurityGuard
import okhttp3.*
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

class UpdateProfileWorker(context: Context, parameters: WorkerParameters)
    : BaseWorker(context, parameters) {

    companion object {
        private val log = LoggerFactory.getLogger(UpdateProfileWorker::class.java)
        const val KEY_PASSWORD = "UpdateProfileRequestWorker.PASSWORD"
        const val KEY_DISPLAY_NAME = "UpdateProfileRequestWorker.DISPLAY_NAME"
        const val KEY_PUBLIC_MESSAGE = "UpdateProfileRequestWorker.PUBLIC_MESSAGE"
        const val KEY_AVATAR_URL = "UpdateProfileRequestWorker.AVATAR_URL"
        const val KEY_USER_ID = "UpdateProfileRequestWorker.KEY_USER_ID"
        const val KEY_CREATED_AT = "UpdateProfileRequestWorker.CREATED_AT"
        const val KEY_LOCAL_AVATAR_URL_TO_UPLOAD = "UpdateProfileRequestWorker.AVATAR_URL_TO_UPLOAD"
        const val KEY_UPLOAD_SERVICE = "UpdateProfileRequestWorker.UPLOAD_SERVICE"
    }

    private val platformRepo = PlatformRepo.getInstance()

    override suspend fun doWorkWithBaseProgress(): Result {
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: ""
        val publicMessage = inputData.getString(KEY_PUBLIC_MESSAGE) ?: ""
        var avatarUrl = inputData.getString(KEY_AVATAR_URL) ?: ""
        if (!inputData.keyValueMap.containsKey(KEY_CREATED_AT))
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_CREATED_AT parameter"))
        val createdAt = inputData.getLong(KEY_CREATED_AT, 0L)
        val blockchainIdentity = platformRepo.getBlockchainIdentity()!!

        val encryptionKey: KeyParameter
        try {
            val password = SecurityGuard().retrievePassword()
            encryptionKey = WalletApplication.getInstance().wallet!!.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        } catch (ex: Exception) {
            when (ex) {
                is GeneralSecurityException,
                is IOException -> {
                    val msg = formatExceptionMessage("retrieve password", ex)
                    return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
                }
                else -> throw ex
            }
        }

        // Perform the image upload here
        val avatarUrlToUpload = inputData.getString(KEY_LOCAL_AVATAR_URL_TO_UPLOAD) ?: ""
        val avatarBytes = try {
            File(avatarUrlToUpload).readBytes()
        } catch (e: Exception) {
            val msg = formatExceptionMessage("load profile picture file", e)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        if (avatarBytes.isEmpty()) {
            val msg = "The profile picture file is empty"
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }


        val uploadService = inputData.getString(KEY_UPLOAD_SERVICE) ?: ""
        if (avatarUrlToUpload.isNotEmpty()) {
            when (uploadService) {
                EditProfileViewModel.GoogleDrive -> {
                    //TODO
                }
                EditProfileViewModel.Imgur -> {
                    avatarUrl = uploadProfilePictureToImgur(avatarBytes) ?: ""
                    if (avatarUrl.isEmpty()) {
                        return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Failed to upload picture to Imgur"))
                    }
                }
            }
        }

        val dashPayProfile = DashPayProfile(blockchainIdentity.uniqueIdString,
                blockchainIdentity.getUniqueUsername(),
                displayName,
                publicMessage,
                avatarUrl,
                createdAt
        )

        return try {
            val profileRequestResult = platformRepo.broadcastUpdatedProfile(dashPayProfile, encryptionKey)
            Result.success(workDataOf(
                    KEY_USER_ID to profileRequestResult.userId
            ))
        } catch (ex: Exception) {
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("create/update profile", ex)))
        }
    }

    private fun uploadProfilePictureToImgur(avatarBytes: ByteArray): String? {
        val imgurUploadUrl = "https://api.imgur.com/3/upload"
        val client = OkHttpClient()

        val imageBodyPart = RequestBody.create(MediaType.parse("image/*jpg"), avatarBytes)
        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("image", "profile.jpg", imageBodyPart).build()

        val request = Request.Builder().url(imgurUploadUrl).post(requestBody).build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body()
            if (responseBody != null && response.isSuccessful) {
                val moshi = Moshi.Builder().build()
                val jsonAdapter = moshi.adapter(ImgurUploadResponse::class.java)
                val imgurUploadResponse = jsonAdapter.fromJson(response.body().toString())
                if (imgurUploadResponse?.success == true && imgurUploadResponse.data != null) {
                    return imgurUploadResponse.data.link
                }
            }
        } catch (e: Exception) {
            log.error(e.message)
        }
        return null
    }

}