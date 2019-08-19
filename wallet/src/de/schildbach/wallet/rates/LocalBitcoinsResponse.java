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
        if (localBitcoinsRate.getAvg1h() != null) {
            return localBitcoinsRate.getAvg1h();
        } else if (localBitcoinsRate.getAvg6h() != null) {
            return localBitcoinsRate.getAvg6h();
        } else if (localBitcoinsRate.getAvg12h() != null) {
            return localBitcoinsRate.getAvg12h();
        } else {
            return localBitcoinsRate.getAvg24h();
        }
    }

}
