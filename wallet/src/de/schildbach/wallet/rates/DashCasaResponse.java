package de.schildbach.wallet.rates;

import com.squareup.moshi.Json;

import java.math.BigDecimal;

/**
 * @author Samuel Barbosa
 */
public class DashCasaResponse {

    @Json(name = "dashrate")
    private final BigDecimal dashVesPrice;

    public DashCasaResponse(BigDecimal dashVesPrice) {
        this.dashVesPrice = dashVesPrice;
    }

    public BigDecimal getDashVesPrice() {
        return dashVesPrice;
    }

}
