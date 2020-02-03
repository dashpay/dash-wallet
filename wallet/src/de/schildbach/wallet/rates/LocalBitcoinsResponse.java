package de.schildbach.wallet.rates;

import com.squareup.moshi.Json;

import java.math.BigDecimal;

public class LocalBitcoinsResponse {

    @Json(name = "VES")
    private final LocalBitcoinsRate localBitcoinsRate;

    public LocalBitcoinsResponse(LocalBitcoinsRate localBitcoinsRate) {
        this.localBitcoinsRate = localBitcoinsRate;
    }

    public BigDecimal getDashVesPrice() {
        if (localBitcoinsRate.avg1h != null) {
            return localBitcoinsRate.avg1h;
        } else if (localBitcoinsRate.avg6h != null) {
            return localBitcoinsRate.avg6h;
        } else if (localBitcoinsRate.avg12h != null) {
            return localBitcoinsRate.avg12h;
        } else {
            return localBitcoinsRate.avg24h;
        }
    }

}
