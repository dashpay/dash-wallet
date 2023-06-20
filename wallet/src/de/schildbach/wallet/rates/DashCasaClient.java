package de.schildbach.wallet.rates;

import com.squareup.moshi.Moshi;

import de.schildbach.wallet.adapter.BigDecimalAdapter;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.http.GET;

/**
 * @author Samuel Barbosa
 */
public class DashCasaClient extends RetrofitClient {

    private static DashCasaClient instance;
    private DashCasaService service;

    public static DashCasaClient getInstance() {
        if (instance == null) {
            instance = new DashCasaClient("https://dash.casa/");
        }
        return instance;
    }

    private DashCasaClient(String baseUrl) {
        super(baseUrl);

        Moshi moshi = moshiBuilder.add(new BigDecimalAdapter()).build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        service = retrofit.create(DashCasaService.class);
    }

    public Response<DashCasaResponse> getRates() throws IOException {
        return service.getRates().execute();
    }

    private interface DashCasaService {
        @GET("api/?cur=VES")
        Call<DashCasaResponse> getRates();
    }

}
