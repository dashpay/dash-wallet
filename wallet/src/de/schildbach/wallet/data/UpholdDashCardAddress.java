package de.schildbach.wallet.data;

import com.squareup.moshi.Json;

public class UpholdDashCardAddress extends UpholdCardAddress {

    @Json(name = "dash")
    private String dashAddress;

    public String getDashAddress() {
        return dashAddress;
    }

    public void setDashAddress(String dashAddress) {
        this.dashAddress = dashAddress;
    }

}
