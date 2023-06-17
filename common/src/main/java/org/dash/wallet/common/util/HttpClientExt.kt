/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.common.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val log = LoggerFactory.getLogger(OkHttpClient::class.java)

fun ResponseBody.decodeBitmap(): Bitmap {
    return BufferedInputStream(byteStream()).use { inputStream ->
        return@use BitmapFactory.decodeStream(inputStream)
    }
}

suspend fun OkHttpClient.get(url: String): Response = call(Request.Builder().url(url).get().build())
suspend fun OkHttpClient.head(url: String): Response = call(Request.Builder().url(url).head().build())

suspend fun OkHttpClient.call(request: Request): Response {
    return suspendCancellableCoroutine { coroutine ->
        newCall(request).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (coroutine.isActive) {
                    log.error("okHttp call failure", e)
                    coroutine.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (coroutine.isActive) {
                    return coroutine.resume(response)
                }
            }
        })
    }
}

fun Response.ensureSuccessful() {
    if (!isSuccessful) {
        log.error("got http error {}: {}", code, message)
        throw IOException("HTTP error: $code; $message")
    }
}
