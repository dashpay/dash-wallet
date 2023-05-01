package de.schildbach.wallet.rates;

import com.squareup.moshi.Moshi;

import org.dash.wallet.common.data.entity.ExchangeRate;

import java.io.IOException;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.http.GET;

/**
 * @author Samuel Barbosa
 */
public class BitcoinAverageClient extends RetrofitClient {

    private static BitcoinAverageClient instance;

    public static BitcoinAverageClient getInstance() {
        if (instance == null) {
            instance = new BitcoinAverageClient("https://apiv2.bitcoinaverage.com/");
        }
        return instance;
    }

    private BitcoinAverageService service;

    private BitcoinAverageClient(String baseUrl) {
        super(baseUrl);

        Moshi moshi = moshiBuilder.add(new BitcoinAverageRateAdapter()).build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        service = retrofit.create(BitcoinAverageService.class);
    }

    public Response<List<ExchangeRate>> getGlobalIndices() throws IOException {
        return service.getGlobalIndices().execute();
    }

    public interface BitcoinAverageService {
        @GET("indices/global/ticker/short?crypto=BTC")
        Call<List<ExchangeRate>> getGlobalIndices();
    }

}
