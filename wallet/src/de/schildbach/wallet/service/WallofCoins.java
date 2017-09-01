package de.schildbach.wallet.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import de.schildbach.wallet.response.GetReceivingOptionsResp.PayFieldsBeanX;
import de.schildbach.wallet.response.PayFieldsDeserializer;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;


public class WallofCoins {

    private static final String TAG = "WallofCoins";

    // TODO need to change url for production
    private static final String API_BASE_URL = "http://woc.reference.genitrust.com/";
//    private static final String API_BASE_URL = "https://wallofcoins.com/";

    public static RestApi createService(Interceptor interceptor) {
        return getClient(interceptor)
                .create(RestApi.class);
    }

    public static RestApi createService() {
        return getClient(null)
                .create(RestApi.class);
    }

    private static Retrofit getClient(Interceptor interceptor) {

        OkHttpClient.Builder httpClient = new OkHttpClient.Builder();

        // add your other interceptors …
        httpClient.connectTimeout(60, TimeUnit.SECONDS);
        httpClient.readTimeout(60, TimeUnit.SECONDS);

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        // set your desired log level
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);
        // add logging as last interceptor
        httpClient.addInterceptor(logging);  // <-- this is the important line!
        if (null != interceptor)
            httpClient.addInterceptor(interceptor);

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(PayFieldsBeanX.class, new PayFieldsDeserializer())
                .create();

        return new Retrofit.Builder()
                .baseUrl(API_BASE_URL).client(httpClient.build())
                .addConverterFactory(GsonConverterFactory.create(gson)).build();
    }

}
