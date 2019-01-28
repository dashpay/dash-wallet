package de.schildbach.wallet.rates;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author Samuel Barbosa
 */
public class PoloniexResponse {

    private final List<List<BigDecimal>> asks;
    private final List<List<BigDecimal>> bids;

    public PoloniexResponse(List<List<BigDecimal>> asks, List<List<BigDecimal>> bids) {
        this.asks = asks;
        this.bids = bids;
    }

    public List<List<BigDecimal>> getAsks() {
        return asks;
    }

    public List<List<BigDecimal>> getBids() {
        return bids;
    }

    public BigDecimal getRate() {
        BigDecimal ask = asks.get(0).get(0);
        BigDecimal bid = bids.get(0).get(0);
        if (ask != null && ask.compareTo(BigDecimal.ZERO) > 0
                && bid != null && bid.compareTo(BigDecimal.ZERO) > 0) {
            return ask.add(bid).divide(BigDecimal.valueOf(2));
        }
        return null;
    }

}
