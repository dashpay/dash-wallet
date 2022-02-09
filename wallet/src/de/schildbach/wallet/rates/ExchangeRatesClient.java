package de.schildbach.wallet.rates;

import androidx.annotation.Nullable;

import org.dash.wallet.common.data.ExchangeRate;

import java.util.List;


/**
 * @author Samuel Barbosa
 */
public interface ExchangeRatesClient {

    @Nullable
    List<ExchangeRate> getRates() throws Exception;

}
