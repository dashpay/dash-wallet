package de.schildbach.wallet.wallofcoins.selling_wizard.api;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp;
import de.schildbach.wallet.wallofcoins.response.PayFieldsDeserializer;
import de.schildbach.wallet_test.BuildConfig;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Created by  on 04-Apr-18.
 */

public class SellingAPIClient {
    private static String API_BASE_URL;

    static final TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[]{};
                }
            }
    };

    /**
     * Method for Create RestAPI call
     *
     * @param interceptor
     * @param context     context object of parent class
     * @return RestApi Client object
     */
    public static SellingApi createService(Interceptor interceptor, Context context) {
        API_BASE_URL = SellingApiConstants.BASE_URL;
        return getClient(interceptor)
                .create(SellingApi.class);
    }

    /**
     * Method for Create RestAPI call
     *
     * @param context context object of parent class
     * @return RestApi Client object
     */
    public static SellingApi createService(Context context) {
        API_BASE_URL = SellingApiConstants.BASE_URL;
        return getClient(null)
                .create(SellingApi.class);
    }

    /**
     * Method for get retrofit
     *
     * @param interceptor
     * @return Retrofit Object
     */
    private static Retrofit getClient(Interceptor interceptor) {

        OkHttpClient.Builder httpClient = new OkHttpClient.Builder();
        // add your other interceptors …
        httpClient.connectTimeout(60, TimeUnit.SECONDS);
        httpClient.readTimeout(60, TimeUnit.SECONDS);
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, "keystore_pass".toCharArray());
            sslContext.init(null, trustAllCerts, new SecureRandom());
            httpClient.sslSocketFactory(sslContext.getSocketFactory())
                    .hostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return true;
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        // set your desired log level
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);
        // add logging as last interceptor
        if (BuildConfig.DEBUG)
            httpClient.addInterceptor(logging);  // <-- this is the important line!
        if (null != interceptor)
            httpClient.addInterceptor(interceptor);

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(GetReceivingOptionsResp.PayFieldsBeanX.class, new PayFieldsDeserializer())
                .create();

        return new Retrofit.Builder()
                .baseUrl(SellingApiConstants.BASE_URL).client(httpClient.build())
                .addConverterFactory(GsonConverterFactory.create(gson)).build();
    }
}
