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
package de.schildbach.wallet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.schildbach.wallet.rates.RetrofitClient
import de.schildbach.wallet_test.BuildConfig
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import kotlin.jvm.Throws

class ImgurClient : RetrofitClient("https://api.imgur.com/3/") {

    private val imgurService: ImgurService

    companion object {
        val instance by lazy { ImgurClient() }
    }

    init {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        imgurService = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi))
                .build().create(ImgurService::class.java)
    }

    @Throws(Exception::class)
    fun upload(file: File): ImgurUploadResponse? {
        val multipartBody = MultipartBody.Part.createFormData("image",
                file.name, RequestBody.create(MediaType.parse("image/*"), file))
        return imgurService.upload(multipartBody).execute().body()
    }

    private interface ImgurService {
        @Multipart
        @Headers("Authorization: Client-ID ${BuildConfig.IMGUR_CLIENT_ID}")
        @POST("upload")
        fun upload(@Part file: MultipartBody.Part): Call<ImgurUploadResponse>
    }
}
