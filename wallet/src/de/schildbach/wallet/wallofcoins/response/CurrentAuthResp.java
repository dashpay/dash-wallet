package de.schildbach.wallet.wallofcoins.response;

import java.util.List;

public class CurrentAuthResp {

    /**
     * phone : 15005550006
     * token : YXV0aDo2OjE0MjE1OTk3MTF8MDQzNDJjYzM2ODg1NTdmODU5Mjk0ZjM5NDA1ODhhZjY3MGQxNDBjMQ==
     * availableAuthSources : ["deviceCode","device"]
     * tokenExpiresAt : 2015-01-18T16:48:31.319Z
     */

    public String phone;
    public String token;
    public String tokenExpiresAt;
    public List<String> availableAuthSources;
}
