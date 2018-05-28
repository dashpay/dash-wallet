package de.schildbach.wallet.wallofcoins.selling_wizard.models;

import java.io.Serializable;

/**
 * Created on 12-Mar-18.
 */

public class SellingWizardPhoneListVO implements Serializable {

    private String phoneNumber = "";
    private String deviceId = "";
    private String emailId="";


    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

}
