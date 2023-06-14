package de.schildbach.wallet.rates;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonReader;

import org.dash.wallet.common.data.entity.ExchangeRate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Samuel Barbosa
 */
public class BitcoinAverageRateAdapter {

    private static final String CURRENCY_PREFIX = "BTC";
    private static final String LEGACY_VENEZUELAN_CURRENCY = "VEF";
    private static final String CURRENT_VENEZUELAN_CURRENCY = "VES";

    @FromJson
    List<ExchangeRate> fromJson(JsonReader jsonReader) throws IOException {
        List<ExchangeRate> rates = new ArrayList<>();

        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            String pairing = jsonReader.nextName();
            if (pairing.startsWith(CURRENCY_PREFIX)) {
                jsonReader.beginObject();
                while (jsonReader.hasNext()) {
                    if (jsonReader.nextName().equalsIgnoreCase("last")) {
                        String currencyCode = pairing.substring(CURRENCY_PREFIX.length());
                        String rate = jsonReader.nextString();
                        if (LEGACY_VENEZUELAN_CURRENCY.equalsIgnoreCase(currencyCode)) {
                            currencyCode = CURRENT_VENEZUELAN_CURRENCY;
                        }
                        rates.add(new ExchangeRate(currencyCode, rate));
                    } else {
                        jsonReader.skipValue();
                    }
                }
                jsonReader.endObject();
            }
        }
        jsonReader.endObject();

        return rates;
    }

}
