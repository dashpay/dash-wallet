package de.schildbach.wallet.data;

import de.schildbach.wallet.Constants;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

public class UpholdClient {

    private static UpholdClient instance;
    private final UpholdService service;

    private UpholdClient() {
        //TODO: Parametrize baseURL according to ENV
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api-sandbox.uphold.com/")
                .addConverterFactory(MoshiConverterFactory.create())
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
                    callback.onSuccess(response.body().getAccessToken());
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

    public interface Callback<T> {
        void onSuccess(T data);
        void onError(Exception e);
    }

}
