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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;

public abstract class RetrofitClient {

    protected Retrofit.Builder retrofitBuilder;
    protected Moshi.Builder moshiBuilder;
    protected Retrofit retrofit;
    protected Executor executor;

    protected RetrofitClient(String baseUrl) {
        OkHttpClient okClient = new OkHttpClient.Builder().build();

        executor = Executors.newSingleThreadExecutor();
        moshiBuilder = new Moshi.Builder();
        retrofitBuilder = new Retrofit.Builder().client(okClient).baseUrl(baseUrl);
    }

    public interface Callback<T> {
        void onSuccess(T data);
        void onError(Exception e, boolean otpRequired);
    }

}
