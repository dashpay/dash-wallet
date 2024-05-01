package de.schildbach.wallet.rates;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

public class DashRetailRate {

    private final String baseCurrency;
    private final String quoteCurrency;
    private final BigDecimal price;
    private final String retrieved;

    public DashRetailRate(String baseCurrency, String quoteCurrency, BigDecimal price, String retrieved) {
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.price = price;
        this.retrieved = retrieved;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public String getQuoteCurrency() {
        return quoteCurrency;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getRetreivalDate() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC")); // Set timezone to UTC to match the 'Z' (Zulu time)

        try {
            return dateFormat.parse(retrieved).getTime();
        } catch (ParseException e) {
            e.printStackTrace();
            return System.currentTimeMillis();
        }
    }

}
