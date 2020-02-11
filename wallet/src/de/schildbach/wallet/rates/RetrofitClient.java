/*
 * Copyright 2014-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.rates;

import com.squareup.moshi.Moshi;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ui.preference.PinRetryController;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Retrofit;

public abstract class RetrofitClient {

    protected Retrofit.Builder retrofitBuilder;
    protected Moshi.Builder moshiBuilder;
    protected Retrofit retrofit;
    protected Executor executor;

    protected RetrofitClient(String baseUrl) {
        PinRetryController pinRetryController = PinRetryController.getInstance();

        OkHttpClient okClient = Constants.HTTP_CLIENT.newBuilder()
                .addInterceptor(new SecureTimeInterceptor(pinRetryController))
                .build();

        executor = Executors.newSingleThreadExecutor();
        moshiBuilder = new Moshi.Builder();
        retrofitBuilder = new Retrofit.Builder().client(okClient).baseUrl(baseUrl);
    }

    public interface Callback<T> {

        void onSuccess(T data);

        void onError(Exception e, boolean otpRequired);
    }

    private class SecureTimeInterceptor implements Interceptor {

        private PinRetryController pinRetryController;

        private SecureTimeInterceptor(PinRetryController pinRetryController) {
            this.pinRetryController = pinRetryController;
        }

        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Request request = chain.request();
            Response response = chain.proceed(request);
            Date headerDate = response.headers().getDate("date");
            if (headerDate != null) {
                pinRetryController.storeSecureTime(headerDate);
            }
            return response;

        }
    }
}
