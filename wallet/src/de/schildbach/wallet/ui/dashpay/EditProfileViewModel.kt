/*
 * Copyright 2020 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui.dashpay

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.api.client.http.HttpRequestInitializer
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.utils.GoogleDriveService
import de.schildbach.wallet.ui.dashpay.utils.ImgurService
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileOperation
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileStatusLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.avatar.ProfilePictureHelper
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.channels.FileChannel
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    val platformRepo: PlatformRepo,
    private val imgurService: ImgurService,
    private val googleDriveService: GoogleDriveService
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {
    enum class ProfilePictureStorageService {
        GOOGLE_DRIVE, IMGUR
    }

    private val log = LoggerFactory.getLogger(EditProfileViewModel::class.java)
    lateinit var storageService: ProfilePictureStorageService
    var pictureSource: String = ""

    val profilePictureUploadLiveData = MutableLiveData<Resource<String>>()
    val uploadDialogAcceptLiveData = MutableLiveData<Boolean>()
    val deleteProfilePictureConfirmationLiveData = MutableLiveData<Boolean>()

    val profilePictureFile by lazy {
        try {
            val storageDir: File = walletApplication.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
            File(storageDir, Constants.Files.PROFILE_PICTURE_FILENAME)
        } catch (ex: IOException) {
            log.error(ex.message, ex)
            null
        }
    }

    val onTmpPictureReadyForEditEvent = SingleLiveEvent<File>()

    lateinit var tmpPictureFile: File

    var avatarHash: Sha256Hash? = null
    var avatarFingerprint: BigInteger? = null

    fun createTmpPictureFile(): Boolean = try {
        val storageDir: File = walletApplication.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        tmpPictureFile = File.createTempFile("profileimagetmp", ".jpg", storageDir).apply {
            deleteOnExit()
        }
        true
    } catch (ex: IOException) {
        log.error(ex.message, ex)
        false
    }

    val updateProfileRequestState = UpdateProfileStatusLiveData(walletApplication)

    private var lastAttemptedProfile: DashPayProfile? = null

    fun broadcastUpdateProfile(
        displayName: String,
        publicMessage: String,
        avatarUrl: String,
        uploadService: String = "",
        localAvatarUrl: String = ""
    ) {
        logProfileInfoEvents(displayName, publicMessage, avatarUrl)

        val dashPayProfile = dashPayProfile.value!!
        val avatarFingerprintBytes = avatarFingerprint?.run { ProfilePictureHelper.toByteArray(this) }
        val updatedProfile = DashPayProfile(
            dashPayProfile.userId,
            dashPayProfile.username,
            displayName,
            publicMessage,
            avatarUrl,
            avatarHash?.bytes,
            avatarFingerprintBytes,
            dashPayProfile.createdAt,
            dashPayProfile.updatedAt
        )

        lastAttemptedProfile = updatedProfile
        viewModelScope.launch {
            // Save updated profile ahead of broadcast to reflect changes in UI immediately
            platformRepo.updateDashPayProfile(updatedProfile)
        }
        UpdateProfileOperation(walletApplication).create(updatedProfile, uploadService, localAvatarUrl).enqueue()
    }

    fun retryBroadcastProfile() {
        if (lastAttemptedProfile != null) {
            UpdateProfileOperation(walletApplication)
                .create(lastAttemptedProfile!!, "", "")
                .enqueue()
        }
    }

    fun clearLastAttemptedProfile() {
        lastAttemptedProfile = null
    }

    fun saveAsProfilePictureTmp(picturePath: String) {
        viewModelScope.launch {
            copyFile(File(picturePath), tmpPictureFile)
            onTmpPictureReadyForEditEvent.postValue(tmpPictureFile)
        }
    }

    private fun copyFile(srcFile: File, outFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inStream = FileInputStream(srcFile)
                val outStream = FileOutputStream(outFile)
                val inChannel: FileChannel = inStream.channel
                val outChannel: FileChannel = outStream.channel
                inChannel.transferTo(0, inChannel.size(), outChannel)
                inStream.close()
                outStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun saveExternalBitmap(bitmap: Bitmap) {
        if (!tmpPictureFile.exists()) {
            tmpPictureFile.createNewFile()
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 100, FileOutputStream(tmpPictureFile))) {
                withContext(Dispatchers.Main) {
                    onTmpPictureReadyForEditEvent.postValue(tmpPictureFile)
                }
            }
        }
    }

    fun uploadProfilePicture(credential: HttpRequestInitializer? = null) {
        when (storageService) {
            ProfilePictureStorageService.IMGUR -> uploadToImgur()
            ProfilePictureStorageService.GOOGLE_DRIVE -> {
                requireNotNull(credential) { "credential must not be null" }
                uploadToGoogleDrive(credential)
            }
        }
    }

    private fun uploadToImgur() {
        viewModelScope.launch {
            profilePictureUploadLiveData.postValue(Resource.loading())
            try {
                val avatarUrl = imgurService.uploadProfilePicture(profilePictureFile!!)
                profilePictureUploadLiveData.postValue(Resource.success(avatarUrl))
            } catch (e: Exception) {
                profilePictureUploadLiveData.postValue(
                    Resource.error(e.message ?: "error")
                )
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun uploadToGoogleDrive(credential: HttpRequestInitializer) {
        viewModelScope.launch {
            profilePictureUploadLiveData.postValue(Resource.loading(""))

            try {
                val secureId = Settings.Secure.getString(walletApplication.contentResolver, Settings.Secure.ANDROID_ID)
                val fileId = googleDriveService.uploadImage(
                    credential,
                    UUID.randomUUID().toString() + ".jpg",
                    profilePictureFile!!.readBytes(),
                    secureId
                )

                log.info("gdrive upload image: complete")
                profilePictureUploadLiveData.postValue(Resource.success("https://drive.usercontent.google.com/download?export=view&id=${fileId}"))
            } catch (e: Exception) {
                log.info("gdrive: upload failure: $e")
                e.printStackTrace()
                profilePictureUploadLiveData.postValue(Resource.error(e))
            }
        }
    }

    fun cancelUploadRequest() {
        if (storageService == ProfilePictureStorageService.IMGUR) {
            imgurService.cancelUploadRequest()
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    private fun logProfileInfoEvents(displayName: String, publicMessage: String, avatarUrl: String) {
        if (displayName != dashPayProfile.value!!.displayName) {
            analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_NAME, mapOf())
            analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_NAME_LENGTH, mapOf(
                AnalyticsConstants.Parameter.VALUE to displayName.length
            ))
        }

        if (publicMessage != dashPayProfile.value!!.publicMessage) {
            analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_ABOUT_ME, mapOf())
            analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_ABOUT_ME_LENGTH, mapOf(
                AnalyticsConstants.Parameter.VALUE to publicMessage.length
            ))
        }

        if (avatarUrl != dashPayProfile.value!!.avatarUrl) {
            analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_PICTURE, mapOf())
            when (pictureSource) {
                "gravatar" -> analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_PICTURE_GRAVATAR, mapOf())
                "public_url" -> analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_PICTURE_PUBLIC_URL, mapOf())
                "camera" -> analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_PICTURE_CAMERA, mapOf())
                "gallery" -> analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_PICTURE_GALLERY, mapOf())
                else -> { }
            }
        }
    }

    suspend fun hasEnoughCredits(): CreditBalanceInfo {
        return platformRepo.getIdentityBalance()
    }
}