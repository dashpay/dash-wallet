package de.schildbach.wallet.data;

import com.squareup.moshi.Json;

public class UpholdCryptoCardAddress {

    @Json(name = "id")
    private String address;
    private String network;

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

}