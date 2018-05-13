package org.dash.wallet.integration.uphold.data;

import com.squareup.moshi.FromJson;

import java.util.Map;

public class UpholdCardAddressAdapter {

    @FromJson UpholdCardAddress fromJson(Map<String, String> json) {
        UpholdCardAddress cardAddress = new UpholdCardAddress();
        cardAddress.setWireId(json.get("wire"));
        cardAddress.setCryptoAddress(json.get("dash"));
        return cardAddress;
    }

}
