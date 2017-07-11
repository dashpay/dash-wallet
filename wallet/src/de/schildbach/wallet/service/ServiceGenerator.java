package de.schildbach.wallet.service;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;


public class ServiceGenerator {

    private static final String TAG = "ServiceGenerator";
    private static final String API_BASE_URL = "http://woc.reference.genitrust.com/";

    public static RestApi createService() {
        return getClient()
                .create(RestApi.class);
    }

    private static Retrofit getClient() {

        OkHttpClient.Builder httpClient = new OkHttpClient.Builder();

        // add your other interceptors …
        httpClient.connectTimeout(60, TimeUnit.SECONDS);
        httpClient.readTimeout(60, TimeUnit.SECONDS);

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        // set your desired log level
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);
        // add logging as last interceptor
        httpClient.addInterceptor(logging);  // <-- this is the important line!

        return new Retrofit.Builder()
                .baseUrl(API_BASE_URL).client(httpClient.build())
                .addConverterFactory(GsonConverterFactory.create()).build();
    }

}
