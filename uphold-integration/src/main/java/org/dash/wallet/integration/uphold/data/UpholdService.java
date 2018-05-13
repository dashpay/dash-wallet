package org.dash.wallet.integration.uphold.data;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface UpholdService {

    @FormUrlEncoded
    @POST("oauth2/token")
    Call<UpholdAccessToken> getAccessToken(@Field("client_id") String clientId,
                                           @Field("client_secret")String clientSecret,
                                           @Field("code") String code,
                                           @Field("grant_type") String grantType);

    @GET("v0/me/cards")
    Call<List<UpholdCard>> getCards();

    @POST("v0/me/cards")
    Call<UpholdCard> createCard(@Body Map<String, String> body);

    @POST("v0/me/cards/{id}/addresses")
    Call<UpholdCryptoCardAddress> createCardAddress(@Path("id") String cardId, @Body Map<String, String> body);

    @POST("v0/me/cards/{cardId}/transactions")
    Call<UpholdTransaction> createTransaction(@Path("cardId") String cardId, @Body Map<String, Object> body);

    @POST("v0/me/cards/{cardId}/transactions/{txId}/commit")
    Call<Object> commitTransaction(@Path("cardId") String cardId, @Path("txId") String txId);

}
