/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import android.provider.Settings
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.services.drive.Drive
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.GoogleDriveService
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.*

@HiltWorker
class UpdateProfileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val analytics: AnalyticsService,
    val platformBroadcastService: PlatformBroadcastService)
    : BaseWorker(context, parameters) {

    companion object {
        private val log = LoggerFactory.getLogger(UpdateProfileWorker::class.java)
        const val KEY_PASSWORD = "UpdateProfileRequestWorker.PASSWORD"
        const val KEY_DISPLAY_NAME = "UpdateProfileRequestWorker.DISPLAY_NAME"
        const val KEY_PUBLIC_MESSAGE = "UpdateProfileRequestWorker.PUBLIC_MESSAGE"
        const val KEY_AVATAR_URL = "UpdateProfileRequestWorker.AVATAR_URL"
        const val KEY_AVATAR_HASH = "UpdateProfileRequestWorker.AVATAR_HASH"
        const val KEY_AVATAR_FINGERPRINT = "UpdateProfileRequestWorker.AVATAR_FINGERPRINT"
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
        val avatarFingerprint = inputData.getByteArray(KEY_AVATAR_FINGERPRINT)
        val avatarHash = inputData.getByteArray(KEY_AVATAR_HASH)
        if (!inputData.keyValueMap.containsKey(KEY_CREATED_AT))
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to UpdateProfileError.DOCUMENT.name))
        val createdAt = inputData.getLong(KEY_CREATED_AT, 0L)
        val blockchainIdentity = platformRepo.blockchainIdentity

        val encryptionKey: KeyParameter
        try {
            val password = SecurityGuard().retrievePassword()
            encryptionKey = WalletApplication.getInstance().wallet!!.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to UpdateProfileError.DECRYPTION.name))
        } catch (ex: Exception) {
            when (ex) {
                is GeneralSecurityException,
                is IOException -> {
                    analytics.logError(ex, "Failed to create/update profile: retrieve password")
                    return Result.failure(workDataOf(KEY_ERROR_MESSAGE to UpdateProfileError.PASSWORD.name))
                }
                else -> throw ex
            }
        }

        // Perform the image upload here
        val avatarUrlToUpload = inputData.getString(KEY_LOCAL_AVATAR_URL_TO_UPLOAD)?:""
        val uploadService = inputData.getString(KEY_UPLOAD_SERVICE)?:""
        if (avatarUrlToUpload.isNotEmpty()) {
            val avatarFile = File(avatarUrlToUpload)
            @Suppress("BlockingMethodInNonBlockingContext")
            when (uploadService) {
                EditProfileViewModel.ProfilePictureStorageService.GOOGLE_DRIVE.name -> {
                    val avatarFileBytes = avatarFile.readBytes()
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
                avatarHash,
                avatarFingerprint,
                createdAt
        )

        return try {
            val profileRequestResult = platformBroadcastService.broadcastUpdatedProfile(dashPayProfile, encryptionKey)
            Result.success(workDataOf(
                    KEY_USER_ID to profileRequestResult.userId
            ))
        } catch (ex: Exception) {
            analytics.logError(ex, "Failed to create/update profile: broadcast state transition")
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
            analytics.logError(t, "Failed to upload to Google Drive")

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