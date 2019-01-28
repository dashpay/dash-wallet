package de.schildbach.wallet.rates;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.ToJson;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * @author Samuel Barbosa
 */
public class DashCentralRateAdapter {

    @ToJson
    String toJson(Rate rate) {
        //Not used, added te prevent retrofit crash
        return null;
    }

    @FromJson
    Rate fromJson(JsonReader jsonReader) throws IOException {
        Rate rate = null;

        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            if (jsonReader.nextName().equalsIgnoreCase("exchange_rates")) {
                jsonReader.beginObject();
                while (jsonReader.hasNext()) {
                    if (jsonReader.nextName().equalsIgnoreCase("btc_dash")) {
                        rate = new Rate(new BigDecimal(jsonReader.nextString()));
                    } else {
                        jsonReader.skipValue();
                    }
                }
                jsonReader.endObject();
            } else {
                jsonReader.skipValue();
            }
        }
        jsonReader.endObject();

        return rate;
    }

}
