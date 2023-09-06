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
package de.schildbach.wallet.ui.dashpay

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.api.services.drive.Drive
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.ImgurUploadResponse
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.dashpay.utils.GoogleDriveService
import org.dash.wallet.common.ui.avatar.ProfilePictureHelper
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileOperation
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileStatusLiveData
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {

    enum class ProfilePictureStorageService {
        GOOGLE_DRIVE, IMGUR
    }

    private val log = LoggerFactory.getLogger(EditProfileViewModel::class.java)
    private var uploadProfilePictureCall: Call? = null
    lateinit var storageService: ProfilePictureStorageService
    private val config by lazy { WalletApplication.getInstance().configuration }
    var googleDrive: Drive? = null
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

    val profilePictureUri: Uri by lazy {
        FileProvider.getUriForFile(walletApplication, "${walletApplication.packageName}.file_attachment", profilePictureFile!!)
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

    var lastAttemptedProfile: DashPayProfile? = null

    fun broadcastUpdateProfile(displayName: String, publicMessage: String, avatarUrl: String,
                               uploadService: String = "", localAvatarUrl: String = "") {

        logProfileInfoEvents(displayName, publicMessage, avatarUrl)

        val dashPayProfile = dashPayProfile.value!!
        val avatarFingerprintBytes = avatarFingerprint?.run { ProfilePictureHelper.toByteArray(this) }
        val updatedProfile = DashPayProfile(dashPayProfile.userId, dashPayProfile.username,
                displayName, publicMessage, avatarUrl, avatarHash?.bytes, avatarFingerprintBytes,
                dashPayProfile.createdAt, dashPayProfile.updatedAt)

        lastAttemptedProfile = updatedProfile

        UpdateProfileOperation(walletApplication).create(updatedProfile, uploadService,
                localAvatarUrl).enqueue()
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

    fun downloadPictureAsync(pictureUrl: String): LiveData<Resource<Response>> {
        val result = MutableLiveData<Resource<Response>>()
        val client = OkHttpClient()
        val request = Request.Builder()
                .url(pictureUrl)
                .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.value = Resource.error(e, null)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body
                if (responseBody != null) {
                    if (!tmpPictureFile.exists()) {
                        tmpPictureFile.createNewFile()
                    }
                    try {
                        val outStream = FileOutputStream(tmpPictureFile)
                        val inChannel = Channels.newChannel(responseBody.byteStream())
                        val outChannel: FileChannel = outStream.channel
                        outChannel.transferFrom(inChannel, 0, Long.MAX_VALUE)
                        inChannel.close()
                        outStream.close()
                        onTmpPictureReadyForEditEvent.postValue(tmpPictureFile)
                        result.postValue(Resource.success(response))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        result.postValue(Resource.error(e, null))
                    }
                } else {
                    result.postValue(Resource.error("error: ${response.code}", null))
                }
            }
        })
        return result
    }

    suspend fun downloadPicture(pictureUrl: String): Response {
        return suspendCoroutine { continuation ->
            val client = OkHttpClient()
            val request = Request.Builder()
                    .url(pictureUrl)
                    .build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
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

    fun uploadProfilePicture() {
        when (storageService) {
            ProfilePictureStorageService.IMGUR -> uploadProfilePictureToImgur(profilePictureFile!!)
            ProfilePictureStorageService.GOOGLE_DRIVE -> uploadToGoogleDrive(googleDrive!!)
        }
    }

    private fun uploadProfilePictureToImgur(file: File) {
        profilePictureUploadLiveData.postValue(Resource.loading())
        viewModelScope.launch(Dispatchers.IO) {
            val imgurUploadUrl = "https://api.imgur.com/3/upload"
            val client = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS).build()

            val requestBuilder = Request.Builder().header("Authorization",
                    "Client-ID ${BuildConfig.IMGUR_CLIENT_ID}")

            //Delete previous profile Picture
            val imgurDeleteHash = config.imgurDeleteHash
            if (imgurDeleteHash.isNotEmpty()) {
                val imgurDeleteUrl = "https://api.imgur.com/3/image/$imgurDeleteHash"
                val deleteRequest = requestBuilder.url(imgurDeleteUrl).delete().build()
                try {
                    uploadProfilePictureCall = client.newCall(deleteRequest)
                    val deleteResponse = uploadProfilePictureCall!!.execute()
                    if (!deleteResponse.isSuccessful) {
                        profilePictureUploadLiveData.postValue(Resource.error(deleteResponse.message))
                        // if we cannot delete it, the cause is probably because the IMGUR_CLIENT_* values
                        // are not specified
                        // for now, clear the delete hash to allow the next upload operation to succeed
                        log.info("imgur: attempt to delete last image failed: check IMGUR_CLIENT_* values")
                        config.imgurDeleteHash = ""
                        return@launch
                    } else {
                        log.info("imgur: delete successful ($imgurDeleteUrl)")
                        config.imgurDeleteHash = ""
                    }
                } catch (e: Exception) {
                    var canceled = false
                    if (e is IOException) {
                        canceled = "Canceled".equals(e.message, true)
                        log.info("imgur: delete canceled ($imgurDeleteUrl)")
                    }
                    if (!canceled) {
                        analytics.logError(e, "Failed to delete profile picture: ImgUr")
                        profilePictureUploadLiveData.postValue(Resource.error(e))
                        log.error("imgur: delete failed ($imgurDeleteUrl): ${e.message}")
                    }
                }
            }

            val avatarBytes = try {
                file.readBytes()
            } catch (e: Exception) {
                profilePictureUploadLiveData.postValue(Resource.error(e))
                return@launch
            }

            val imageBodyPart = RequestBody.create("image/*jpg".toMediaTypeOrNull(), avatarBytes)
            val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("image", "profile.jpg", imageBodyPart).build()

            val uploadRequest = requestBuilder.url(imgurUploadUrl).post(requestBody).build()

            try {
                uploadProfilePictureCall = client.newCall(uploadRequest)
                val response = uploadProfilePictureCall!!.execute()
                val responseBody = response.body
                if (responseBody != null && response.isSuccessful) {
                    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                    val jsonAdapter = moshi.adapter(ImgurUploadResponse::class.java)
                    val imgurUploadResponse = jsonAdapter.fromJson(responseBody.string())
                    log.info("imgur: response: $imgurUploadResponse")
                    if (imgurUploadResponse?.success == true && imgurUploadResponse.data != null) {
                        config.imgurDeleteHash = imgurUploadResponse.data.deletehash
                        val avatarUrl = imgurUploadResponse.data.link
                        Log.d("AvatarUrl", avatarUrl)
                        log.info("imgur: upload successful (${response.code})")
                        profilePictureUploadLiveData.postValue(Resource.success(avatarUrl))
                    } else {
                        log.error("imgur: upload failed: response invalid")
                        profilePictureUploadLiveData.postValue(Resource.error(response.message))
                        analytics.logError(Exception(response.message), "Failed to upload profile picture: ImgUr")
                    }
                } else {
                    log.error("imgur: upload failed (${response.code}): ${response.message}")
                    analytics.logError(Exception(response.message), "Failed to upload profile picture: ImgUr")
                    profilePictureUploadLiveData.postValue(Resource.error(response.message))
                }
            } catch (e: Exception) {
                var canceled = false
                if (e is IOException) {
                    canceled = "Canceled".equals(e.message, true)
                    log.info("imgur: upload cancelled")
                }
                if (!canceled) {
                    profilePictureUploadLiveData.postValue(Resource.error(e))
                    log.error("imgur: upload failed: ${e.message}", e)
                    analytics.logError(e, "Failed to upload profile picture: ImgUr")
                }
            }
        }
    }

    @SuppressLint("HardwareIds")
    fun uploadToGoogleDrive(drive: Drive) {

        viewModelScope.launch(Dispatchers.IO) {
            profilePictureUploadLiveData.postValue(Resource.loading(""))
            try {
                val secureId = Settings.Secure.getString(walletApplication.contentResolver, Settings.Secure.ANDROID_ID)
                val fileId = GoogleDriveService.uploadImage(drive,
                        UUID.randomUUID().toString() + ".jpg",
                        profilePictureFile!!.readBytes(), secureId)

                log.info("gdrive upload image: complete")
                profilePictureUploadLiveData.postValue(Resource.success("https://drive.google.com/uc?export=view&id=${fileId}"))
            } catch (e: Exception) {
                log.info("gdrive: upload failure: $e")
                e.printStackTrace()
                profilePictureUploadLiveData.postValue(Resource.error(e))
            }
        }
    }

    fun cancelUploadRequest() {
        uploadProfilePictureCall?.cancel()
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
            when (pictureSource) {
                "gravatar" -> analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_PICTURE_GRAVATAR, mapOf())
                "public_url" -> analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_PICTURE_PUBLIC_URL, mapOf())
                "camera" -> analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_PICTURE_CAMERA, mapOf())
                "gallery" -> analytics.logEvent(AnalyticsConstants.UsersContacts.PROFILE_CHANGE_PICTURE_GALLERY, mapOf())
                else -> { }
            }
        }
    }
}