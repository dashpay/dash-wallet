package com.dash.wallet.integration.uphold.data;

import com.squareup.moshi.Json;

public class UpholdAccessToken {

    @Json(name = "access_token")
    private String accessToken;

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

}
