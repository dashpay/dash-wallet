package de.schildbach.wallet.wallofcoins.api;

import java.util.List;
import java.util.Map;

import de.schildbach.wallet.wallofcoins.request.CreateAuthReq;
import de.schildbach.wallet.wallofcoins.response.AdsListActivityResp;
import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.ConfirmDepositResp;
import de.schildbach.wallet.wallofcoins.response.CreateAdResp;
import de.schildbach.wallet.wallofcoins.response.CreateAuthResp;
import de.schildbach.wallet.wallofcoins.response.CreateDeviceResp;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet.wallofcoins.response.CurrentAuthResp;
import de.schildbach.wallet.wallofcoins.response.DiscoveryInputsResp;
import de.schildbach.wallet.wallofcoins.response.GetAuthTokenResp;
import de.schildbach.wallet.wallofcoins.response.GetCurrencyResp;
import de.schildbach.wallet.wallofcoins.response.GetHoldsResp;
import de.schildbach.wallet.wallofcoins.response.GetOffersResp;
import de.schildbach.wallet.wallofcoins.response.GetPricingOptionsResp;
import de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp;
import de.schildbach.wallet.wallofcoins.response.OrderListResp;
import de.schildbach.wallet.wallofcoins.response.SendVerificationResp;
import de.schildbach.wallet.wallofcoins.response.VerifyAdResp;
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

/**
 * RestApi Client Interface For all WOC RestFull API with Method
 * Get,Post,Update & Delete API call
 */
public interface RestApi {

    @GET("api/v1/auth/current/")
    Call<CurrentAuthResp> getCurrentAuth();

    @POST("api/v1/auth/")
    Call<CreateAuthResp> createAuth(@Body CreateAuthReq createAuthReq);

    @GET("api/v1/orders/")
    Call<List<OrderListResp>> getOrders(@Query("publisherId") String publisherId);


    @GET("api/v1/auth/{phone}/")
    Call<CheckAuthResp> checkAuth(@Path("phone") String username, @Query("publisherId") String publisherId);

    @DELETE("api/v1/auth/{phone}/")
    Call<CheckAuthResp> deleteAuth(@Path("phone") String username, @Query("publisherId") String publisherId);

    @DELETE("api/v1/orders/{orderId}/")
    Call<Void> cancelOrder(@Path("orderId") String orderId, @Query("publisherId") String publisherId);

    @FormUrlEncoded
    @POST("api/v1/auth/{phone}/authorize/")
    Call<GetAuthTokenResp> getAuthToken(@Path("phone") String username, @FieldMap Map<String, String> partMap);

    @GET("api/v1/banks/")
    Call<List<GetReceivingOptionsResp>> getReceivingOptions(@Query("country") String country, @Query("publisherId") String publisherId);

    @GET("api/v1/ad/")
    Call<List<AdsListActivityResp>> getAdsListing();

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
    Call<GetOffersResp> getOffers(@Path("discoveryId") String discoveryId, @Query("publisherId") String publisherId);

    @FormUrlEncoded
    @POST("api/v1/holds/")
    Call<CreateHoldResp> createHold(@FieldMap Map<String, String> partMap);

    @GET("api/v1/holds/")
    Call<List<GetHoldsResp>> getHolds();

    @DELETE("api/v1/holds/{id}/")
    Call<Void> deleteHold(@Path("id") String id);

    @FormUrlEncoded
    @POST("api/v1/holds/{id}/capture/")
    Call<List<CaptureHoldResp>> captureHold(@Path("id") String id, @FieldMap Map<String, String> partMap);

    @FormUrlEncoded
    @POST("api/v1/orders/{holdId}/confirmDeposit/")
    Call<ConfirmDepositResp> confirmDeposit(@Path("holdId") String holdId, @Field("your_field") String yourField, @Query("publisherId") String publisherId);

    @FormUrlEncoded
    @POST("api/v1/devices/")
    Call<CreateDeviceResp> createDevice(@FieldMap Map<String, String> partMap);

    @GET("api/v1/devices/")
    Call<List<CreateDeviceResp>> getDevice();

}
