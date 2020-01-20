package de.schildbach.wallet.rates;

import com.squareup.moshi.Json;

import java.math.BigDecimal;

public class LocalBitcoinsResponse {

    @Json(name = "VES")
    private final LocalBitcoinsRate vesRate;

    public LocalBitcoinsResponse(LocalBitcoinsRate vesRate) {
        this.vesRate = vesRate;
    }

    public BigDecimal getDashVesPrice() {
        if (vesRate.avg1h != null) {
            return vesRate.avg1h;
        } else if (vesRate.avg6h != null) {
            return vesRate.avg6h;
        } else if (vesRate.avg12h != null) {
            return vesRate.avg12h;
        } else {
            return vesRate.avg24h;
        }
    }
}

