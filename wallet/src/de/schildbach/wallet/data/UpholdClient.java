package de.schildbach.wallet.data;

import android.util.Log;

import com.squareup.moshi.Moshi;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.schildbach.wallet.Constants;
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

    private String accessToken;
    private List<UpholdCard> cards;

    private Interceptor headerInterceptor = new Interceptor() {

        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            if (accessToken != null) {
                Request request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer " + accessToken).build();
                return chain.proceed(request);
            }
            return chain.proceed(chain.request());
        }

    };

    private UpholdClient() {
        //TODO: Parametrize baseURL according to ENV
        OkHttpClient okClient = new OkHttpClient.Builder().addInterceptor(headerInterceptor).build();

        Moshi moshi = new Moshi.Builder().add(new UpholdCardAddressAdapter()).build();

        Retrofit retrofit = new Retrofit.Builder()
                .client(okClient)
                .baseUrl("https://api-sandbox.uphold.com/")
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build();

        this.service = retrofit.create(UpholdService.class);
    }

    public static UpholdClient getInstance() {
        if (instance == null) {
            instance = new UpholdClient();
        }
        return instance;
    }

    public void getAccessToken(String code, final Callback<String> callback) {
        service.getAccessToken(Constants.UPHOLD_CLIENT_ID, Constants.UPHOLD_CLIENT_SECRET, code,
                "authorization_code").enqueue(new retrofit2.Callback<AccessToken>() {
            @Override
            public void onResponse(Call<AccessToken> call, Response<AccessToken> response) {
                if (response.isSuccessful()) {
                    accessToken = response.body().getAccessToken();
                    getCards(new Callback<List<UpholdCard>>() {
                        @Override
                        public void onSuccess(List<UpholdCard> cards) {
                                UpholdClient.this.cards = cards;
                        }

                        @Override
                        public void onError(Exception e) {
                            e.printStackTrace();
                        }
                    });
                    callback.onSuccess(accessToken);
                } else {
                    callback.onError(new Exception(response.message()));
                }
            }

            @Override
            public void onFailure(Call<AccessToken> call, Throwable t) {
                callback.onError(new Exception(t));
            }
        });
    }

    public void getCards(final Callback<List<UpholdCard>> callback) {
        service.getCards().enqueue(new retrofit2.Callback<List<UpholdCard>>() {
            @Override
            public void onResponse(Call<List<UpholdCard>> call, Response<List<UpholdCard>> response) {
                if (response.isSuccessful()) {
                    callback.onSuccess(response.body());

                    UpholdCard dashCard = getDashCard(response.body());
                    if (dashCard == null) {
                        createDashCard();
                    } else {
                        if (dashCard.getAddress().getCryptoAddress() == null) {
                            createDashAddress(dashCard.getId());
                        }
                        //TODO: Store Dash Card
                        Log.d("Dash Card", dashCard.toString());
                    }
                } else {
                    callback.onError(new Exception(response.message()));
                }
            }

            @Override
            public void onFailure(Call<List<UpholdCard>> call, Throwable t) {
                callback.onError(new Exception(t));
            }
        });
    }

    private void createDashCard() {
        Map<String, String> body = new HashMap<>();
        body.put("label", "Dash Card");
        body.put("currency", "DASH");
        service.createCard(body).enqueue(new retrofit2.Callback<UpholdCard>() {
            @Override
            public void onResponse(Call<UpholdCard> call, Response<UpholdCard> response) {
                if (response.isSuccessful()) {
                    createDashAddress(response.body().getId());
                } else {
                    //TODO: Handle error
                }
                Log.d("Response", response.toString());
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
                Log.d("Uphold", "Address created: " + response.body().getAddress());
            }

            @Override
            public void onFailure(Call<UpholdCryptoCardAddress> call, Throwable t) {
                t.printStackTrace();
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

    public interface Callback<T> {
        void onSuccess(T data);
        void onError(Exception e);
    }

}
