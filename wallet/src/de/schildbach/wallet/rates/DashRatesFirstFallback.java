package de.schildbach.wallet.rates;

import android.support.annotation.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author Samuel Barbosa
 */
public class DashRatesFirstFallback implements ExchangeRatesClient {

    private static DashRatesFirstFallback instance;
    private static final String VES_CURRENCY_CODE = "VES";

    public static DashRatesFirstFallback getInstance() {
        if (instance == null) {
            instance = new DashRatesFirstFallback();
        }
        return instance;
    }

    private DashRatesFirstFallback() {

    }

    @Nullable
    @Override
    public List<ExchangeRate> getRates() throws Exception {
        BitcoinAverageClient btcAvgClient = BitcoinAverageClient.getInstance();
        CryptoCompareClient cryptoCompareClient = CryptoCompareClient.getInstance();

        List<ExchangeRate> rates = btcAvgClient.getGlobalIndices().body();
        CryptoCompareVesBtcRate vesBtcRateResponse = cryptoCompareClient.getVESBTCRate().body();
        Rate dashBtcRate = cryptoCompareClient.getDashCustomAverage().body();

        if (rates == null || rates.isEmpty() || vesBtcRateResponse == null || dashBtcRate == null) {
            throw new IllegalStateException("Failed to fetch prices from Fallback1");
        }

        for(ExchangeRate rate : rates) {
            BigDecimal currencyBtcRate;
            if (VES_CURRENCY_CODE.equalsIgnoreCase(rate.getCurrencyCode())) {
                currencyBtcRate = vesBtcRateResponse.getRate();
            } else {
                currencyBtcRate = new BigDecimal(rate.getRate());
            }
            rate.setRate(currencyBtcRate.multiply(dashBtcRate.getRate()).toPlainString());
        }

        return rates;
    }

}
