package de.schildbach.wallet.rates;

import com.squareup.moshi.Json;

import java.math.BigDecimal;

public class LocalBitcoinsRate {
    @Json(name = "avg_1h")
    public BigDecimal avg1h;
    @Json(name = "avg_6h")
    public BigDecimal avg6h;
    @Json(name = "avg_12h")
    public BigDecimal avg12h;
    @Json(name = "avg_24h")
    public BigDecimal avg24h;
}
