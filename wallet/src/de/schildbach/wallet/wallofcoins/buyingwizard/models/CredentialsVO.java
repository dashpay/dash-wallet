package de.schildbach.wallet.wallofcoins.buyingwizard.models;

import java.io.Serializable;

/**
 * Created by  on 12-Mar-18.
 */

public class CredentialsVO implements Serializable{

    private String phoneNumber = "";
    private String deviceToken = "";


    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public void setDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }


}
