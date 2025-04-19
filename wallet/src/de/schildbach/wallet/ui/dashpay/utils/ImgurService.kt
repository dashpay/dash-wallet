package de.schildbach.wallet.ui.dashpay.utils

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.schildbach.wallet.data.ImgurUploadResponse
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ImgurService @Inject constructor(
    private val analytics: AnalyticsService,
    private val config: Configuration
) {
    private val log = LoggerFactory.getLogger(ImgurService::class.java)
    private var uploadProfilePictureCall: Call? = null

    suspend fun uploadProfilePicture(file: File): String = withContext(Dispatchers.IO) {
        val imgurUploadUrl = "https://api.imgur.com/3/upload"
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()

        val requestBuilder = Request.Builder().header("Authorization",
            "Client-ID ${BuildConfig.IMGUR_CLIENT_ID}")

        // Delete previous profile Picture
        val imgurDeleteHash = config.imgurDeleteHash
        if (imgurDeleteHash.isNotEmpty()) {
            deleteImage(client, requestBuilder, imgurDeleteHash)
        }

        val avatarBytes = file.readBytes()

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
                    log.info("imgur: upload successful (${response.code})")
                    return@withContext avatarUrl
                } else {
                    log.error("imgur: upload failed: response invalid")
                    analytics.logError(Exception(response.message), "Failed to upload profile picture: ImgUr")
                    throw Exception(response.message)
                }
            } else {
                log.error("imgur: upload failed (${response.code}): ${response.message}")
                analytics.logError(Exception(response.message), "Failed to upload profile picture: ImgUr")
                throw Exception(response.message)
            }
        } catch (e: Exception) {
            var canceled = false
            if (e is IOException) {
                canceled = "Canceled".equals(e.message, true)
                log.info("imgur: upload cancelled")
            }
            if (!canceled) {
                log.error("imgur: upload failed: ${e.message}", e)
                analytics.logError(e, "Failed to upload profile picture: ImgUr")
            }
            throw e
        }
    }

    private fun deleteImage(client: OkHttpClient, requestBuilder: Request.Builder, imgurDeleteHash: String) {
        val imgurDeleteUrl = "https://api.imgur.com/3/image/$imgurDeleteHash"
        val deleteRequest = requestBuilder.url(imgurDeleteUrl).delete().build()
        try {
            uploadProfilePictureCall = client.newCall(deleteRequest)
            val deleteResponse = uploadProfilePictureCall!!.execute()
            if (!deleteResponse.isSuccessful) {
                // if we cannot delete it, the cause is probably because the IMGUR_CLIENT_* values
                // are not specified
                // for now, clear the delete hash to allow the next upload operation to succeed
                log.info("imgur: attempt to delete last image failed: check IMGUR_CLIENT_* values")
                config.imgurDeleteHash = ""
                throw Exception(deleteResponse.message)
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
                log.error("imgur: delete failed ($imgurDeleteUrl): ${e.message}")
            }
            throw e
        }
    }

    fun cancelUploadRequest() {
        uploadProfilePictureCall?.cancel()
    }
} 