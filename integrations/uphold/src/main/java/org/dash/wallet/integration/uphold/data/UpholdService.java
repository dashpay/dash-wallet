/*
 * Copyright 2015-present the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.integration.uphold.data;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Url;

public interface UpholdService {

    @FormUrlEncoded
    @POST("oauth2/token")
    Call<UpholdAccessToken> getAccessToken(@Field("client_id") String clientId,
                                           @Field("client_secret") String clientSecret,
                                           @Field("code") String code,
                                           @Field("grant_type") String grantType);

    @FormUrlEncoded
    @POST("oauth2/revoke")
    Call<String> revokeAccessToken(@Field("token") String token);

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

    @GET
    Call<String> getUpholdCurrency(@Header("Range") String range, @Url String url);


}
