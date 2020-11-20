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

import android.app.Application
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.ImgurUploadResponse
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.SingleLiveEvent
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileOperation
import de.schildbach.wallet.ui.dashpay.work.UpdateProfileStatusLiveData
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class EditProfileViewModel(application: Application) : BaseProfileViewModel(application) {

    enum class ProfilePictureStorageService {
        GOOGLE_DRIVE, IMGUR
    }

    private val log = LoggerFactory.getLogger(EditProfileViewModel::class.java)
    private var uploadProfilePictureCall: Call? = null
    lateinit var storageService: ProfilePictureStorageService
    private val config by lazy { WalletApplication.getInstance().configuration }

    val profilePictureUploadLiveData = MutableLiveData<Resource<String>>()
    val imgurDialogAcceptLiveData = MutableLiveData<Boolean>()
    val deleteProfilePictureConfirmationLiveData = MutableLiveData<Boolean>()


    val profilePictureFile by lazy {
        try {
            val storageDir: File = application.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
            File(storageDir, Constants.Files.PROFILE_PICTURE_FILENAME)
        } catch (ex: IOException) {
            log.error(ex.message, ex)
            null
        }
    }

    val onTmpPictureReadyForEditEvent = SingleLiveEvent<File>()

    lateinit var tmpPictureFile: File

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

    val updateProfileRequestState = UpdateProfileStatusLiveData(application)

    fun broadcastUpdateProfile(displayName: String, publicMessage: String, avatarUrl: String,
                               uploadService: String = "", localAvatarUrl: String = "") {
        val dashPayProfile = dashPayProfileData.value!!
        val updatedProfile = DashPayProfile(dashPayProfile.userId, dashPayProfile.username,
                displayName, publicMessage, avatarUrl,
                dashPayProfile.createdAt, dashPayProfile.updatedAt)
        UpdateProfileOperation(walletApplication).create(updatedProfile, uploadService,
                localAvatarUrl).enqueue()
    }

    fun saveAsProfilePictureTmp(picturePath: String) {
        viewModelScope.launch() {
            copyFile(File(picturePath), tmpPictureFile)
            onTmpPictureReadyForEditEvent.call(tmpPictureFile)
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
                val responseBody = response.body()
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
                    result.postValue(Resource.error("error: ${response.code()}", null))
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
            ProfilePictureStorageService.GOOGLE_DRIVE -> {
                //TODO: Upload to Google Drive
            }
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
                        profilePictureUploadLiveData.postValue(Resource.error(deleteResponse.message()))
                        return@launch
                    } else {
                        config.imgurDeleteHash = ""
                    }
                } catch (e: Exception) {
                    var canceled = false
                    if (e is IOException) {
                        canceled = "Canceled".equals(e.message, true)
                    }
                    if (!canceled) {
                        profilePictureUploadLiveData.postValue(Resource.error(e))
                        log.error(e.message)
                    }
                }
            }

            val avatarBytes = try {
                file.readBytes()
            } catch (e: Exception) {
                profilePictureUploadLiveData.postValue(Resource.error(e))
                return@launch
            }

            val imageBodyPart = RequestBody.create(MediaType.parse("image/*jpg"), avatarBytes)
            val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("image", "profile.jpg", imageBodyPart).build()

            val uploadRequest = requestBuilder.url(imgurUploadUrl).post(requestBody).build()

            try {
                uploadProfilePictureCall = client.newCall(uploadRequest)
                val response = uploadProfilePictureCall!!.execute()
                val responseBody = response.body()
                if (responseBody != null && response.isSuccessful) {
                    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                    val jsonAdapter = moshi.adapter(ImgurUploadResponse::class.java)
                    val imgurUploadResponse = jsonAdapter.fromJson(responseBody.string())
                    if (imgurUploadResponse?.success == true && imgurUploadResponse.data != null) {
                        config.imgurDeleteHash = imgurUploadResponse.data.deletehash
                        val avatarUrl = imgurUploadResponse.data.link
                        Log.d("AvatarUrl", avatarUrl)
                        dashPayProfile?.avatarUrl = avatarUrl
                        profilePictureUploadLiveData.postValue(Resource.success(avatarUrl))
                    } else {
                        profilePictureUploadLiveData.postValue(Resource.error(response.message()))
                    }
                } else {
                    profilePictureUploadLiveData.postValue(Resource.error(response.message()))
                }
            } catch (e: Exception) {
                var canceled = false
                if (e is IOException) {
                    canceled = "Canceled".equals(e.message, true)
                }
                if (!canceled) {
                    profilePictureUploadLiveData.postValue(Resource.error(e))
                    log.error(e.message)
                }
            }
        }
    }

    fun cancelUploadRequest() {
        uploadProfilePictureCall?.cancel()
    }

}