package org.dash.android.lightpayprot

import org.dash.android.lightpayprot.data.SimplifiedPayment
import org.dash.android.lightpayprot.data.SimplifiedPaymentAck
import org.dash.android.lightpayprot.data.SimplifiedPaymentRequest
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

interface PaymentApi {
    /**
     * @GET declares an HTTP GET request
     * @Path("user") annotation on the userId parameter marks it as a
     * replacement for the {user} placeholder in the @GET path
     */
    @Headers("Accept: " + SimplifiedPaymentProtocol.MIME_TYPE_PAYMENT_REQUEST)
    @GET
    suspend fun getPaymentRequest(@Url url: String): Response<SimplifiedPaymentRequest>

    /**
     * @GET declares an HTTP GET request
     * @Path("user") annotation on the userId parameter marks it as a
     * replacement for the {user} placeholder in the @GET path
     */
    @Headers("Accept: " + SimplifiedPaymentProtocol.MIME_TYPE_PAYMENT)
    @POST
    suspend fun postPayment(@Url paymentUrl: String, payment: SimplifiedPayment): Response<SimplifiedPaymentAck>
}