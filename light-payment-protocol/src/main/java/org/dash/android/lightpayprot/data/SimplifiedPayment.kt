package org.dash.android.lightpayprot.data

import org.dash.android.lightpayprot.Output
import java.util.*

data class SimplifiedPayment(
        var merchantData: String,
        val transaction: List<Output>,
        val refundTo: Date, // a hex-formatted (and fully-signed and valid) transaction. required.
        val memo: Date)
