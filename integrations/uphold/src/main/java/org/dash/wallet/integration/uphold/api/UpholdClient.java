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

import org.dash.wallet.integration.uphold.data.UpholdApiException;
import org.dash.wallet.integration.uphold.data.UpholdCard;
import org.dash.wallet.integration.uphold.data.UpholdConstants;
import org.dash.wallet.integration.uphold.data.UpholdTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kotlin.Deprecated;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Response;

public class UpholdClient {

    private static UpholdClient instance;
    final UpholdService service;
    final SharedPreferences prefs;
    private final String encryptionKey;
    String accessToken;
    private String otpToken;
    UpholdCard dashCard;
    final Map<String, List<String>> requirements = new HashMap<>();

    static final Logger log = LoggerFactory.getLogger(UpholdClient.class);

    private static final String UPHOLD_PREFS = "uphold_prefs.xml";
    static final String UPHOLD_ACCESS_TOKEN = "access_token";

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
        this.accessToken = UpholdClientExtKt.getStoredAccessToken(this);

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

    @Deprecated(message = "Inject instead")
    public static UpholdClient getInstance() {
        if (instance == null) {
            throw new IllegalStateException("You must call UpholdClient#init() first");
        }
        return instance;
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
}
