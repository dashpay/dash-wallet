package de.schildbach.wallet.service;

import java.util.List;

import de.schildbach.wallet.request.CreateAuthReq;
import de.schildbach.wallet.request.GetAuthTokenReq;
import de.schildbach.wallet.response.CreateAuthResp;
import de.schildbach.wallet.response.GetAuthTokenResp;
import de.schildbach.wallet.response.GetReceivingOptionsResp;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;


public interface RestApi {

    @POST("api/v1/auth/")
    Call<CreateAuthResp> createAuth(@Body CreateAuthReq createAuthReq);

    @POST("api/v1/auth/{phone}")
    Call<GetAuthTokenResp> getAuthToken(@Path("phone") String username, @Body GetAuthTokenReq createAuthReq);

    @GET("api/v1/banks")
    Call<List<GetReceivingOptionsResp>> getReceivingOptions(@Query("country") String country);

}

