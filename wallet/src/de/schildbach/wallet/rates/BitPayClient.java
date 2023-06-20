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
public class BitPayClient extends RetrofitClient {

    private static BitPayClient instance;

    public static BitPayClient getInstance() {
        if (instance == null) {
            instance = new BitPayClient("https://bitpay.com/");
        }
        return instance;
    }

    private BitPayService service;

    private BitPayClient(String baseUrl) {
        super(baseUrl);

        Moshi moshi = moshiBuilder.add(new BigDecimalAdapter()).build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        service = retrofit.create(BitPayService.class);
    }

    public Response<BitPayResponse> getRates() throws IOException {
        return service.getRates().execute();
    }

    private interface BitPayService {
        @GET("rates")
        Call<BitPayResponse> getRates();
    }

}
