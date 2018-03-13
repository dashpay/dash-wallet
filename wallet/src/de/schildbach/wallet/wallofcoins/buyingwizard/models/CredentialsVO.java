package de.schildbach.wallet.wallofcoins.buyingwizard.models;

/**
 * Created by  on 12-Mar-18.
 */

public class CredentialsVO {

    private String phoneNumber = "";

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    private String authToken = "";


    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }




}
