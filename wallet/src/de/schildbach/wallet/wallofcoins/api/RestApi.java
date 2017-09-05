package de.schildbach.wallet.wallofcoins.api;

import java.util.List;
import java.util.Map;

import de.schildbach.wallet.request.CreateAuthReq;
import de.schildbach.wallet.request.GetAuthTokenReq;
import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet.response.ConfirmDepositResp;
import de.schildbach.wallet.response.CreateAdResp;
import de.schildbach.wallet.response.CreateAuthResp;
import de.schildbach.wallet.response.CreateHoldResp;
import de.schildbach.wallet.response.CurrentAuthResp;
import de.schildbach.wallet.response.DiscoveryInputsResp;
import de.schildbach.wallet.response.GetAuthTokenResp;
import de.schildbach.wallet.response.GetCurrencyResp;
import de.schildbach.wallet.response.GetOffersResp;
import de.schildbach.wallet.response.GetPricingOptionsResp;
import de.schildbach.wallet.response.GetReceivingOptionsResp;
import de.schildbach.wallet.response.SendVerificationResp;
import de.schildbach.wallet.response.VerifyAdResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.OrderListResp;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;


public interface RestApi {

    @GET("api/v1/auth/current/")
    Call<CurrentAuthResp> getCurrentAuth();

    @POST("api/v1/auth/")
    Call<CreateAuthResp> createAuth(@Body CreateAuthReq createAuthReq);

    @GET("api/v1/orders/")
    Call<List<OrderListResp>> getOrders();

    @GET("api/v1/auth/{phone}/")
    Call<CheckAuthResp> checkAuth(@Path("phone") String username);

    @DELETE("api/v1/orders/{orderId}/")
    Call<Void> cancelOrder(@Path("orderId") String orderId);

    @POST("api/v1/auth/{phone}/authorize/")
    Call<GetAuthTokenResp> getAuthToken(@Path("phone") String username, @Body GetAuthTokenReq createAuthReq);

    @GET("api/v1/banks")
    Call<List<GetReceivingOptionsResp>> getReceivingOptions(@Query("country") String country);


    @GET("api/v1/markets/{crypto}/{currency}/")
    Call<List<GetPricingOptionsResp>> getPricingOptions(@Path("crypto") String crypto, @Path("currency") String currency);

    @GET("api/v1/currency/")
    Call<List<GetCurrencyResp>> getCurrency();

    @FormUrlEncoded
    @POST("api/adcreate/")
    Call<CreateAdResp> createAd(@FieldMap Map<String, Object> partMap);

    @FormUrlEncoded
    @POST("api/sendVerification/")
    Call<SendVerificationResp> sendVerification(@FieldMap Map<String, Object> partMap);

    @FormUrlEncoded
    @POST("api/verifyAd/")
    Call<VerifyAdResp> verifyAd(@FieldMap Map<String, String> partMap);


    @FormUrlEncoded
    @POST("api/v1/discoveryInputs/")
    Call<DiscoveryInputsResp> discoveryInputs(@FieldMap Map<String, String> partMap);

    @GET("api/v1/discoveryInputs/{discoveryId}/offers/")
    Call<GetOffersResp> getOffers(@Path("discoveryId") String discoveryId);

    @FormUrlEncoded
    @POST("api/v1/holds/")
    Call<CreateHoldResp> createHold(@FieldMap Map<String, String> partMap);

    @FormUrlEncoded
    @POST("api/v1/holds/{id}/capture/")
    Call<List<CaptureHoldResp>> captureHold(@Path("id") String id, @FieldMap Map<String, String> partMap);

    @FormUrlEncoded
    @POST("api/v1/orders/{holdId}/confirmDeposit/")
    Call<ConfirmDepositResp> confirmDeposit(@Path("holdId") String holdId, @Field("your_field") String yourField);

}

