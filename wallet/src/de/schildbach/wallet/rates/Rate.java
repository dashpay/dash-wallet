package de.schildbach.wallet.rates;

import java.math.BigDecimal;

/**
 * @author Samuel Barbosa
 */
public class Rate {

    private final BigDecimal rate;

    public Rate(BigDecimal rate) {
        this.rate = rate;
    }

    public BigDecimal getRate() {
        return rate;
    }

}
