package de.schildbach.wallet.rates;

import java.math.BigDecimal;

public class LocalBitcoinsRate {

    private final BigDecimal avg1h;
    private final BigDecimal avg6h;
    private final BigDecimal avg12h;
    private final BigDecimal avg24h;

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
