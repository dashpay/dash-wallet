/*
 * Copyright 2014-2015 the original author or authors.
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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.securepreferences.SecurePreferences;
import com.squareup.moshi.Moshi;

import org.dash.wallet.common.data.BigDecimalAdapter;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

public class UpholdClient {

    private static UpholdClient instance;
    private final UpholdService service;
    private final SharedPreferences prefs;
    private final String encryptionKey;
    private String accessToken;
    private String otpToken;
    private UpholdCard dashCard;

    public static final String UPHOLD_AUTH_REDIRECT_URL = "www.dash.org";
    private static final String UPHOLD_PREFS = "uphold_prefs.xml";
    private static final String UPHOLD_ACCESS_TOKEN = "access_token";

    private static final String OTP_REQUIRED_KEY = "OTP-Token";
    private static final String OTP_REQUIRED_VALUE = "required";

    private Interceptor headerInterceptor = new Interceptor() {

        @Override
        public okhttp3.Response intercept(Interceptor.Chain chain) throws IOException {
            if (accessToken != null) {
                Request.Builder requestBuilder = chain.request().newBuilder();
                requestBuilder.addHeader("Authorization", "Bearer " + accessToken);
                if (otpToken != null) {
                    requestBuilder.addHeader("OTP-Token", otpToken);
                }
                return chain.proceed(requestBuilder.build());
            }
            return chain.proceed(chain.request());
        }

    };

    private UpholdClient(Context context, String prefsEncryptionKey) {
        this.encryptionKey = prefsEncryptionKey;
        this.prefs = new SecurePreferences(context, prefsEncryptionKey, UPHOLD_PREFS);
        this.accessToken = getStoredAccessToken();

        String baseUrl = UpholdConstants.CLIENT_BASE_URL;
        OkHttpClient okClient = new OkHttpClient.Builder().addInterceptor(headerInterceptor).build();

        Moshi moshi = new Moshi.Builder()
                .add(new BigDecimalAdapter())
                .add(new UpholdCardAddressAdapter()).build();

        Retrofit retrofit = new Retrofit.Builder()
                .client(okClient)
                .baseUrl(baseUrl)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build();

        this.service = retrofit.create(UpholdService.class);
    }

    public static UpholdClient init(Context context, String prefsEncryptionKey) {
        instance = new UpholdClient(context, prefsEncryptionKey);
        return instance;
    }

    public static UpholdClient getInstance() {
        if (instance == null) {
            throw new IllegalStateException("You must call UpholdClient#init() first");
        }
        return instance;
    }

    public void getAccessToken(String code, final Callback<String> callback) {
        service.getAccessToken(UpholdConstants.CLIENT_ID, UpholdConstants.CLIENT_SECRET, code,
                "authorization_code").enqueue(new retrofit2.Callback<UpholdAccessToken>() {
            @Override
            public void onResponse(Call<UpholdAccessToken> call, Response<UpholdAccessToken> response) {
                if (response.isSuccessful()) {
                    accessToken = response.body().getAccessToken();
                    storeAccessToken();
                    getCards(callback, null);
                } else {
                    callback.onError(new Exception(response.message()), false);
                }
            }

            @Override
            public void onFailure(Call<UpholdAccessToken> call, Throwable t) {
                callback.onError(new Exception(t), false);
            }
        });
    }

    private void storeAccessToken() {
        prefs.edit().putString(UPHOLD_ACCESS_TOKEN, accessToken).apply();
    }

    private String getStoredAccessToken() {
        return prefs.getString(UPHOLD_ACCESS_TOKEN, null);
    }

    private void getCards(final Callback<String> callback, final Callback<UpholdCard> getDashCardCb) {
        service.getCards().enqueue(new retrofit2.Callback<List<UpholdCard>>() {
            @Override
            public void onResponse(Call<List<UpholdCard>> call, Response<List<UpholdCard>> response) {
                if (response.isSuccessful()) {
                    dashCard = getDashCard(response.body());
                    if (dashCard == null) {
                        createDashCard(callback, getDashCardCb);
                    } else {
                        if (dashCard.getAddress().getCryptoAddress() == null) {
                            createDashAddress(dashCard.getId());
                        }
                        callback.onSuccess(dashCard.getId());
                        if (getDashCardCb != null) {
                            getDashCardCb.onSuccess(dashCard);
                        }
                    }
                } else {
                    callback.onError(new Exception(response.message()), false);
                }
            }

            @Override
            public void onFailure(Call<List<UpholdCard>> call, Throwable t) {
                callback.onError(new Exception(t), false);
            }
        });
    }

    private void createDashCard(final Callback<String> callback, final Callback<UpholdCard> getDashCardCb) {
        Map<String, String> body = new HashMap<>();
        body.put("label", "Dash Card");
        body.put("currency", "DASH");
        service.createCard(body).enqueue(new retrofit2.Callback<UpholdCard>() {
            @Override
            public void onResponse(Call<UpholdCard> call, Response<UpholdCard> response) {
                if (response.isSuccessful()) {
                    dashCard = response.body();
                    String dashCardId = dashCard.getId();
                    callback.onSuccess(dashCardId);
                    createDashAddress(dashCardId);
                    if (getDashCardCb != null) {
                        getDashCardCb.onSuccess(response.body());
                    }
                } else {
                    //TODO: Handle error
                }
            }

            @Override
            public void onFailure(Call<UpholdCard> call, Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void createDashAddress(String cardId) {
        Map<String, String> body = new HashMap<>();
        body.put("network", "dash");
        service.createCardAddress(cardId, body).enqueue(new retrofit2.Callback<UpholdCryptoCardAddress>() {
            @Override
            public void onResponse(Call<UpholdCryptoCardAddress> call, Response<UpholdCryptoCardAddress> response) {
                Log.d("Uphold", "UpholdAddress created: " + response.body().getAddress());
            }

            @Override
            public void onFailure(Call<UpholdCryptoCardAddress> call, Throwable t) {
                //TODO: Handle error
            }
        });
    }

    private UpholdCard getDashCard(List<UpholdCard> cards) {
        for (UpholdCard card : cards) {
            if(card.getCurrency().equalsIgnoreCase("dash")) {
                return card;
            }
        }
        return null;
    }

    public void getDashBalance(final Callback<BigDecimal> callback) {
        getCards(new Callback<String>() {
            @Override
            public void onSuccess(String data) {

            }

            @Override
            public void onError(Exception e, boolean otpRequired) {

            }
        }, new Callback<UpholdCard>() {
            @Override
            public void onSuccess(UpholdCard card) {
                callback.onSuccess(new BigDecimal(card.getAvailable()));
            }

            @Override
            public void onError(Exception e, boolean otpRequired) {

            }
        });
    }

    public void createDashWithdrawalTransaction(String amount, String address,
                                                final Callback<UpholdTransaction> callback) {
        HashMap<String, Object> body = new HashMap<>();
        HashMap<String, String> denomination = new HashMap<>();
        denomination.put("amount", amount);
        denomination.put("currency", "DASH");
        body.put("denomination", denomination);
        body.put("destination", address);

        service.createTransaction(dashCard.getId(), body).enqueue(new retrofit2.Callback<UpholdTransaction>() {
            @Override
            public void onResponse(Call<UpholdTransaction> call, Response<UpholdTransaction> response) {
                if (response.isSuccessful()) {
                    callback.onSuccess(response.body());
                } else {
                    boolean otpRequired = OTP_REQUIRED_VALUE.equals(response.headers().get(OTP_REQUIRED_KEY));
                    callback.onError(new Exception(response.errorBody().toString()), otpRequired);
                }
            }

            @Override
            public void onFailure(Call<UpholdTransaction> call, Throwable t) {
                callback.onError(new Exception(t), false);
            }
        });
    }

    public void commitTransaction(String txId, final Callback<Object> callback) {
        service.commitTransaction(dashCard.getId(), txId).enqueue(new retrofit2.Callback<Object>() {
            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful()) {
                    callback.onSuccess(null);
                    otpToken = null;
                } else {
                    boolean otpRequired = OTP_REQUIRED_VALUE.equals(response.headers().get(OTP_REQUIRED_KEY));
                    //Check for invalid token error
                    if (!otpRequired && otpToken != null) {
                        try {
                            JSONObject errorBody = new JSONObject(response.errorBody().string());
                            otpRequired = errorBody.getJSONObject("errors").has("token");
                            otpToken = null;
                        } catch (Exception e) {
                            //No invalid token error found
                        }
                    }
                    callback.onError(new Exception(response.errorBody().toString()), otpRequired);
                }
            }

            @Override
            public void onFailure(Call call, Throwable t) {
                callback.onError(new Exception(t), false);
            }
        });
    }

    public void setOtpToken(String otpToken) {
        this.otpToken = otpToken;
    }

    public boolean isAuthenticated() {
        return getStoredAccessToken() != null;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public interface Callback<T> {
        void onSuccess(T data);
        void onError(Exception e, boolean otpRequired);
    }

}
