package de.schildbach.wallet.rates;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.ToJson;

import org.dash.wallet.common.data.entity.ExchangeRate;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Samuel Barbosa
 */
public class ExchangeRateListMoshiAdapter {

    @ToJson
    String toJson(List<ExchangeRate> value) {
        JSONObject exchangeRatesJSON = new JSONObject();
        for (ExchangeRate rate : value) {
            try {
                exchangeRatesJSON.put(rate.getCurrencyCode(), rate.getRate());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return exchangeRatesJSON.toString();
    }

    @FromJson
    List<ExchangeRate> fromJson(JsonReader jsonReader) throws IOException {
        List<ExchangeRate> list = new ArrayList<>();

        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            String currency = jsonReader.nextName();
            list.add(new ExchangeRate(currency, jsonReader.nextString()));
        }

        return list;
    }

}
