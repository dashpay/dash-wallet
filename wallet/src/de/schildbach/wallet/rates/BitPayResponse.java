package de.schildbach.wallet.rates;

import com.squareup.moshi.Json;

import java.util.List;

/**
 * @author Samuel Barbosa
 */
public class BitPayResponse {

    @Json(name = "data")
    private final List<BitPayRate> rates;

    public BitPayResponse(List<BitPayRate> rates) {
        this.rates = rates;
    }

    public List<BitPayRate> getRates() {
        return rates;
    }

}
