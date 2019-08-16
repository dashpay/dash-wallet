package de.schildbach.wallet.rates;

import com.squareup.moshi.Json;

import java.math.BigDecimal;

public class LocalBitcoinsRate {

    @Json(name = "avg_1h")
    private BigDecimal avg1h;

    @Json(name = "avg_6h")
    private BigDecimal avg6h;

    @Json(name = "avg_12h")
    private BigDecimal avg12h;

    @Json(name = "avg_24h")
    private BigDecimal avg24h;

    public LocalBitcoinsRate(BigDecimal avg1h, BigDecimal avg6h, BigDecimal avg12h, BigDecimal avg24h) {
        this.avg1h = avg1h;
        this.avg6h = avg6h;
        this.avg12h = avg12h;
        this.avg24h = avg24h;
    }

    public BigDecimal getAvg1h() {
        return avg1h;
    }

    public BigDecimal getAvg6h() {
        return avg6h;
    }

    public BigDecimal getAvg12h() {
        return avg12h;
    }

    public BigDecimal getAvg24h() {
        return avg24h;
    }
}
