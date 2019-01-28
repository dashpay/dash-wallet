package de.schildbach.wallet.rates;

import java.math.BigDecimal;

/**
 * @author Samuel Barbosa
 */
public class BitPayRate {

    private final String code;
    private final String name;
    private final BigDecimal rate;

    public BitPayRate(String code, String name, BigDecimal rate) {
        this.code = code;
        this.name = name;
        this.rate = rate;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getRate() {
        return rate;
    }

}
