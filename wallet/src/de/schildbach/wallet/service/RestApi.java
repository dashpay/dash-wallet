package de.schildbach.wallet.service;

import com.google.gson.JsonObject;

import java.util.List;

import de.schildbach.wallet.request.CreateAuthReq;
import de.schildbach.wallet.request.GetAuthTokenReq;
import de.schildbach.wallet.request.SendVerificationReq;
import de.schildbach.wallet.request.VerifyAdReq;
import de.schildbach.wallet.response.CreateAdResp;
import de.schildbach.wallet.response.CreateAuthResp;
import de.schildbach.wallet.response.GetAuthTokenResp;
import de.schildbach.wallet.response.GetPricingOptionsResp;
import de.schildbach.wallet.response.GetReceivingOptionsResp;
import de.schildbach.wallet.response.SendVerificationResp;
import de.schildbach.wallet.response.VerifyAdResp;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;


public interface RestApi {

    @POST("api/v1/auth/")
    Call<CreateAuthResp> createAuth(@Body CreateAuthReq createAuthReq);

    @POST("api/v1/auth/{phone}/authorize/")
    Call<GetAuthTokenResp> getAuthToken(@Path("phone") String username, @Body GetAuthTokenReq createAuthReq);

    @GET("api/v1/banks")
    Call<List<GetReceivingOptionsResp>> getReceivingOptions(@Query("country") String country);

    @GET("api/v1/markets/DASH/USD/")
    Call<List<GetPricingOptionsResp>> getPricingOptions();

    @POST("api/adcreate/")
    Call<CreateAdResp> createAd(@Body JsonObject map);

    @POST("api/sendVerification/")
    Call<SendVerificationResp> sendVerification(@Body SendVerificationReq sendVerificationReqq);

    @POST("api/verifyAd/")
    Call<VerifyAdResp> verifyAd(@Body VerifyAdReq verifyAdReq);
}

