package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import android.provider.Settings
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.tasks.Tasks
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.services.drive.Drive
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.GoogleDriveService
import de.schildbach.wallet.ui.security.SecurityGuard
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import java.io.File
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.*
import java.util.concurrent.Executors

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
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to UpdateProfileError.DOCUMENT.name))
        val createdAt = inputData.getLong(KEY_CREATED_AT, 0L)
        val blockchainIdentity = platformRepo.getBlockchainIdentity()!!

        val encryptionKey: KeyParameter
        try {
            val password = SecurityGuard().retrievePassword()
            encryptionKey = WalletApplication.getInstance().wallet!!.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to UpdateProfileError.DECRYPTION.name))
        } catch (ex: Exception) {
            when (ex) {
                is GeneralSecurityException,
                is IOException -> {
                    val msg = formatExceptionMessage("retrieve password", ex)
                    return Result.failure(workDataOf(KEY_ERROR_MESSAGE to UpdateProfileError.PASSWORD.name))
                }
                else -> throw ex
            }
        }

        // Perform the image upload here
        val avatarUrlToUpload = inputData.getString(KEY_LOCAL_AVATAR_URL_TO_UPLOAD)?:""
        val uploadService = inputData.getString(KEY_UPLOAD_SERVICE)?:""
        if (avatarUrlToUpload.isNotEmpty()) {
            when (uploadService) {
                EditProfileViewModel.ProfilePictureStorageService.GOOGLE_DRIVE.name -> {
                    val avatarFileBytes = File(avatarUrlToUpload).readBytes()
                    val fileId = saveToGoogleDrive(applicationContext, avatarFileBytes)
                    avatarUrl = "https://drive.google.com/uc?export=view&id=$fileId"
                }
                EditProfileViewModel.ProfilePictureStorageService.IMGUR.name -> {
                    //TODO:
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
            //TODO: Use this to trigger a failure
            //Result.failure(workDataOf(
            //        KEY_ERROR_MESSAGE to UpdateProfileError.BROADCAST.name))
        } catch (ex: Exception) {
            formatExceptionMessage("create/update profile", ex)
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to UpdateProfileError.BROADCAST.name))
        }
    }

    private fun saveToGoogleDrive(context: Context, encryptedBackup: ByteArray): String? {
        return try {
            val account: GoogleSignInAccount = GoogleDriveService.getSigninAccount(context)
                    ?: throw GoogleAuthException()

            // 1 - retrieve existing backup so we know whether we have to create a new one, or update existing file
            val drive: Drive? = Objects.requireNonNull(GoogleDriveService.getDriveServiceFromAccount(context, account), "drive service must not be null")

            // 2 - upload the image
            val uploadedAvatarFilename = UUID.randomUUID().toString()
            val secureId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            return GoogleDriveService.uploadImage(drive!!, uploadedAvatarFilename, encryptedBackup, secureId)
        } catch (t: Throwable) {
            //log.error("failed to save channels backup on google drive", t)
            if (t is GoogleAuthIOException || t is GoogleAuthException) {
                //BackupHelper.GoogleDrive.disableGDriveBackup(context)
            } else if (t.cause != null) {
                val cause = t.cause
                if (cause is GoogleAuthIOException || cause is GoogleAuthException) {
                    //BackupHelper.GoogleDrive.disableGDriveBackup(context)
                }
            }
            null
        }
    }
}