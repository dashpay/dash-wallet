package de.schildbach.wallet.wallofcoins.selling_wizard.models;

/**
 * Created by  on 04-Apr-18.
 */

public class SignUpResponseVo {

    private String createdOn;

    private String phone;

    private String email;

    private String phoneVerified;

    private String accessedOn;

    private String lastVerified;

    public String getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(String createdOn) {
        this.createdOn = createdOn;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhoneVerified() {
        return phoneVerified;
    }

    public void setPhoneVerified(String phoneVerified) {
        this.phoneVerified = phoneVerified;
    }

    public String getAccessedOn() {
        return accessedOn;
    }

    public void setAccessedOn(String accessedOn) {
        this.accessedOn = accessedOn;
    }

    public String getLastVerified() {
        return lastVerified;
    }

    public void setLastVerified(String lastVerified) {
        this.lastVerified = lastVerified;
    }

    @Override
    public String toString() {
        return "ClassPojo [createdOn = " + createdOn + ", phone = " + phone + ", email = " + email + ", phoneVerified = " + phoneVerified + ", accessedOn = " + accessedOn + ", lastVerified = " + lastVerified + "]";
    }
}
