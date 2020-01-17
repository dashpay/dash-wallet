package de.schildbach.wallet.rates;

import com.squareup.moshi.Moshi;

import org.dash.wallet.common.data.BigDecimalAdapter;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.http.GET;

public class LocalBitcoinsClient extends RetrofitClient {

    private static LocalBitcoinsClient instance;
    private LocalBitcoinsService service;

    public static LocalBitcoinsClient getInstance() {
        if (instance == null) {
            instance = new LocalBitcoinsClient("https://localbitcoins.com/");
        }
        return instance;
    }

    private LocalBitcoinsClient(String baseUrl) {
        super(baseUrl);

        Moshi moshi = moshiBuilder.add(new BigDecimalAdapter()).build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        service = retrofit.create(LocalBitcoinsService.class);
    }

    public Response<LocalBitcoinsResponse> getRates() throws IOException {
        return service.getRates().execute();
    }

    private interface LocalBitcoinsService {
        @GET("bitcoinaverage/ticker-all-currencies/")
        Call<LocalBitcoinsResponse> getRates();
    }

}
