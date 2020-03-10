package org.dash.android.lightpayprot

data class ApiError(
        var statusCode: Int,
        val error: String,
        val message: String
)
