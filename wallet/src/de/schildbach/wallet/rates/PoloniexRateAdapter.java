package de.schildbach.wallet.rates;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.ToJson;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * @author Samuel Barbosa
 */
public class PoloniexRateAdapter {

    @ToJson
    String toJson(Rate rate) {
        //Not used, added te prevent retrofit crash
        return null;
    }


    @FromJson
    Rate fromJson(JsonReader jsonReader) throws IOException {
        Rate rate = null;

        BigDecimal ask = null;
        BigDecimal bid = null;

        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            if (jsonReader.nextName().equalsIgnoreCase("asks")) {
                ask = getRate(jsonReader);
            } else if (jsonReader.nextName().equalsIgnoreCase("bids")) {
                bid = getRate(jsonReader);
            } else {
                jsonReader.skipValue();
            }
        }
        jsonReader.endObject();

        if (ask != null && ask.compareTo(BigDecimal.ZERO) > 0
                && bid != null && bid.compareTo(BigDecimal.ZERO) > 0) {
            rate = new Rate(ask.add(bid).divide(BigDecimal.valueOf(2)));
        }

        return rate;
    }

    private BigDecimal getRate(JsonReader jsonReader) throws IOException {
        BigDecimal rate = null;
        jsonReader.beginArray();

        while (jsonReader.hasNext()) {
            jsonReader.beginArray();
            rate = new BigDecimal(jsonReader.nextString());
            jsonReader.skipValue();
            jsonReader.endArray();
        }

        jsonReader.endArray();
        return rate;
    }

}
