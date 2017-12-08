package de.schildbach.wallet.wallofcoins.response;

import java.util.List;

/**
 * Created by VIJAY on 04-Sep-17.
 */

public class CheckAuthResp {

    /**
     * phone : 17439995953
     * availableAuthSources : ["deviceCode"]
     */

    private String phone;
    private List<String> availableAuthSources;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public List<String> getAvailableAuthSources() {
        return availableAuthSources;
    }

    public void setAvailableAuthSources(List<String> availableAuthSources) {
        this.availableAuthSources = availableAuthSources;
    }
}
