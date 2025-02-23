package de.schildbach.wallet.data

import androidx.annotation.Keep

@Keep
data class ImgurUploadResponse(val status: Int, val success: Boolean, val data: ImgurImg?)

