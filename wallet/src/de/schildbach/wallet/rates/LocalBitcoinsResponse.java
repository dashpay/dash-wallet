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
        if (vesRate.getAvg1h() != null) {
            return vesRate.getAvg1h();
        } else if (vesRate.getAvg6h() != null) {
            return vesRate.getAvg6h();
        } else if (vesRate.getAvg12h() != null) {
            return vesRate.getAvg12h();
        } else {
            return vesRate.getAvg24h();
        }
    }

}
