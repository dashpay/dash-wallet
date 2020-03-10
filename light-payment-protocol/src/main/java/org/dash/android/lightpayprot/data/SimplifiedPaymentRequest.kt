package org.dash.android.lightpayprot.data

import org.dash.android.lightpayprot.Output
import java.util.*

data class SimplifiedPaymentRequest(
        var network: String,
        val outputs: List<Output>,
        val creationTimestamp: Date,
        val expirationTimestamp: Date,
        val memo: String,
        val paymentUrl: String,
        val merchantData: String) {

    var payeeVerifiedBy: String? = null
    var payeeName: String? = null
}
