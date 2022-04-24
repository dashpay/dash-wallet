/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integration.uphold.api;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.securepreferences.SecurePreferences;

import org.dash.wallet.integration.uphold.data.UpholdAccessToken;
import org.dash.wallet.integration.uphold.data.UpholdApiException;
import org.dash.wallet.integration.uphold.data.UpholdCapability;
import org.dash.wallet.integration.uphold.data.UpholdCard;
import org.dash.wallet.integration.uphold.data.UpholdConstants;
import org.dash.wallet.integration.uphold.data.UpholdCryptoCardAddress;
import org.dash.wallet.integration.uphold.data.UpholdException;
import org.dash.wallet.integration.uphold.data.UpholdTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Response;

public class UpholdClient {

    private static UpholdClient instance;
    private final UpholdService service;
    private final SharedPreferences prefs;
    private final String encryptionKey;
    private String accessToken;
    private String otpToken;
    private UpholdCard dashCard;
    private final Map<String, List<String>> requirements = new HashMap<>();

    private static final Logger log = LoggerFactory.getLogger(UpholdClient.class);

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
        HttpLoggingInterceptor loggingIntercepter = new HttpLoggingInterceptor(message -> log.info(message));
        loggingIntercepter.setLevel(HttpLoggingInterceptor.Level.BODY);
        loggingIntercepter.redactHeader("Authorization");
        OkHttpClient okClient = new OkHttpClient.Builder()
                .addInterceptor(headerInterceptor)
                .addInterceptor(loggingIntercepter)
                .build();

        this.service = new RemoteDataSource().buildApi(UpholdService.class, baseUrl, okClient);
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
                    log.info("Uphold access token obtained");
                    accessToken = response.body().getAccessToken();
                    storeAccessToken();
                    getCards(callback, null);
                } else {
                    log.error("Error obtaining Uphold access token " + response.message() + " code: " + response.code());
                    callback.onError(new UpholdException("Error obtaining Uphold access token", response.message(), response.code()), false);
                }
            }

            @Override
            public void onFailure(Call<UpholdAccessToken> call, Throwable t) {
                log.error("Error to obtain Uphold access token " + t.getMessage());
                callback.onError(new Exception(t), false);
            }
        });
    }

    public void revokeAccessToken(final Callback<String> callback) {
        service.revokeAccessToken(accessToken).enqueue(new retrofit2.Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {
                if (response.isSuccessful() && response.body().equals("OK")) {
                    log.info("Uphold access token revoked");
                    accessToken = null;
                    storeAccessToken();
                    callback.onSuccess(response.body());
                } else {
                    log.error("Error revoking Uphold access token: " + response.message() + " code: " + response.code());
                    callback.onError(new UpholdException("Error revoking Uphold access token", response.message(), response.code()), false);
                }
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                log.error("Error revoking Uphold access token: " + t.getMessage());
                callback.onError(new Exception(t), false);
            }
        });
    }

    public void checkCapabilities() {
        String operation = "withdrawals";
        service.getCapabilities(operation).enqueue(new retrofit2.Callback<UpholdCapability>() {
            @Override
            public void onResponse(@NonNull Call<UpholdCapability> call, @NonNull Response<UpholdCapability> response) {
                if (response.isSuccessful()) {
                    UpholdCapability capability = response.body();

                    if (capability != null && capability.getKey().equals(operation)) {
                        requirements.put(capability.getKey(), capability.getRequirements());
                    }
                } else {
                    log.error("Error obtaining Uphold capabilities: " + response.message() + "; code: " + response.code());
                }
            }

            @Override
            public void onFailure(@NonNull Call<UpholdCapability> call, @NonNull Throwable t) {
                log.error("Error obtaining capabilities: " + t.getMessage());
            }
        });
    }

    public @NonNull List<String> getWithdrawalRequirements() {
        List<String> result = requirements.get("withdrawals");
        return result == null ? Collections.emptyList() : result;
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
                    log.info("get cards success");
                    dashCard = getDashCard(response.body());
                    if (dashCard == null) {
                        log.info("Dash Card not available");
                        createDashCard(callback, getDashCardCb);
                    } else {
                        if (dashCard.getAddress().getCryptoAddress() == null) {
                            log.info("Dash Card has no addresses");
                            createDashAddress(dashCard.getId());
                        }
                        callback.onSuccess(dashCard.getId());
                        if (getDashCardCb != null) {
                            getDashCardCb.onSuccess(dashCard);
                        }
                    }
                } else {
                    log.error("Error obtaining cards " + response.message() + " code: " + response.code());
                    callback.onError(new UpholdException("Error obtaining cards", response.message(), response.code()), false);
                }
            }

            @Override
            public void onFailure(Call<List<UpholdCard>> call, Throwable t) {
                log.error("Error obtaining cards: " + t.getMessage());
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
                    log.info("Dash Card created successfully");
                    dashCard = response.body();
                    String dashCardId = dashCard.getId();
                    callback.onSuccess(dashCardId);
                    createDashAddress(dashCardId);
                    if (getDashCardCb != null) {
                        getDashCardCb.onSuccess(response.body());
                    }
                } else {
                    log.error("Error creating Dash Card: " + response.message() + " code: " + response.code());
                    callback.onError(new UpholdException("Error creating Dash Card", response.message(), response.code()), false);
                }
            }

            @Override
            public void onFailure(Call<UpholdCard> call, Throwable t) {
                log.error("Error creating Dash Card " + t.getMessage());
            }
        });
    }

    private void createDashAddress(String cardId) {
        Map<String, String> body = new HashMap<>();
        body.put("network", "dash");
        service.createCardAddress(cardId, body).enqueue(new retrofit2.Callback<UpholdCryptoCardAddress>() {
            @Override
            public void onResponse(Call<UpholdCryptoCardAddress> call, Response<UpholdCryptoCardAddress> response) {
                log.info("Dash Card address created");
            }

            @Override
            public void onFailure(Call<UpholdCryptoCardAddress> call, Throwable t) {
                log.error("Error creating Dash Card address: " + t.getMessage());
            }
        });
    }

    private UpholdCard getDashCard(List<UpholdCard> cards) {
        for (UpholdCard card : cards) {
            if (card.getCurrency().equalsIgnoreCase("dash")) {
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
                log.error("Error loading Dash balance: " + e.getMessage());
                if (e instanceof UpholdException) {
                    UpholdException ue = (UpholdException) e;
                    if (ue.getCode() == 401) {
                        //we don't have the correct access token, let's logout
                        accessToken = null;
                        storeAccessToken();
                    }
                }
                callback.onError(e, otpRequired);
            }
        }, new Callback<UpholdCard>() {
            @Override
            public void onSuccess(UpholdCard card) {
                log.info("Dash balance loaded");
                callback.onSuccess(new BigDecimal(card.getAvailable()));
            }

            @Override
            public void onError(Exception e, boolean otpRequired) {
                log.error("Error loading Dash balance: " + e.getMessage());
                callback.onError(e, otpRequired);
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
                    log.info("Transaction created successfully");
                    callback.onSuccess(response.body());
                } else {
                    log.info("Error creating transaction: " + response.message() + " code: " + response.code());
                    boolean otpRequired = OTP_REQUIRED_VALUE.equals(response.headers().get(OTP_REQUIRED_KEY));
                    callback.onError(new UpholdApiException(response), otpRequired);
                }
            }

            @Override
            public void onFailure(Call<UpholdTransaction> call, Throwable t) {
                log.info("Error creating transaction " + t.getMessage());
                callback.onError(new Exception(t), false);
            }
        });
    }

    public void commitTransaction(String txId, final Callback<Object> callback) {
        service.commitTransaction(dashCard.getId(), txId).enqueue(new retrofit2.Callback<Object>() {
            @Override
            public void onResponse(Call call, Response response) {
                if (response.isSuccessful()) {
                    log.info("Transaction committed successfully");
                    callback.onSuccess(null);
                    otpToken = null;
                } else {
                    log.info("Error committing transaction: " + response.message() + "code: " + response.code());
                    UpholdApiException upholdApiException = new UpholdApiException(response);
                    boolean otpRequired = OTP_REQUIRED_VALUE.equals(response.headers().get(OTP_REQUIRED_KEY));
                    //Check for invalid token error
                    if (!otpRequired && otpToken != null) {
                        otpRequired = upholdApiException.isTokenError();
                        otpToken = null;
                    }
                    callback.onError(upholdApiException, otpRequired);
                }
            }

            @Override
            public void onFailure(Call call, Throwable t) {
                log.error("Error committing transaction:" + t.getMessage());
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

    public UpholdCard getCurrentDashCard() {
        return dashCard;
    }

    public interface Callback<T> {
        void onSuccess(T data);

        void onError(Exception e, boolean otpRequired);
    }

    public interface CallbackFilter<T> {
        void onSuccess(T data, String length);

        void onError(Exception e, boolean otpRequired);
    }

    public void getUpholdCurrency(String rangeString, final CallbackFilter<String> callback) {

        service.getUpholdCurrency(rangeString, UpholdConstants.UPHOLD_CURRENCY_LIST).enqueue(new retrofit2.Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {
                System.out.println("Response::" + response);
                String length = "";
                if (rangeString == "items=0-50") {

                    length = response.headers().get("content-range");
                }

                System.out.println("ContentLength::" + length);


                if (response.isSuccessful()) {
                    callback.onSuccess(response.body(), length);
                } else {
                    log.error("Error obtaining Uphold access token " + response.message() + " code: " + response.code());
                    callback.onError(new UpholdException("Error obtaining Uphold access token", response.message(), response.code()), false);
                }
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                callback.onError(new Exception(t), false);
            }
        });
    }
}
