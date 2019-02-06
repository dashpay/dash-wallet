package de.schildbach.wallet.rates;

import com.squareup.moshi.Moshi;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.http.GET;

/**
 * @author Samuel Barbosa
 */
public class DashCentralClient extends RetrofitClient {

    private static DashCentralClient instance;

    public static DashCentralClient getInstance() {
        if (instance == null) {
            instance = new DashCentralClient("https://www.dashcentral.org/");
        }
        return instance;
    }

    private DashCentralService service;

    private DashCentralClient(String baseUrl) {
        super(baseUrl);

        Moshi moshi = moshiBuilder.add(new DashCentralRateAdapter()).build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        service = retrofit.create(DashCentralService.class);
    }

    public Response<Rate> getDashBtcPrice() throws IOException {
        return service.getDashBtcPrice().execute();
    }

    private interface DashCentralService {
        @GET("api/v1/public")
        Call<Rate> getDashBtcPrice();
    }

}
