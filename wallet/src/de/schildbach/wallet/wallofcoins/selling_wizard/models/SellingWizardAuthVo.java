package de.schildbach.wallet.wallofcoins.selling_wizard.models;

/**
 * Created on 04-Apr-18.
 */

public class SellingWizardAuthVo {

    private String createdOn;

    private String phone;

    private String email;

    private String token;

    private String authSource;

    private String tokenExpiresAt;

    private String accessedOn;

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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getAuthSource() {
        return authSource;
    }

    public void setAuthSource(String authSource) {
        this.authSource = authSource;
    }

    public String getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(String tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public String getAccessedOn() {
        return accessedOn;
    }

    public void setAccessedOn(String accessedOn) {
        this.accessedOn = accessedOn;
    }

    @Override
    public String toString() {
        return "ClassPojo [createdOn = " + createdOn + ", phone = " + phone + ", email = " + email + ", token = " + token + ", authSource = " + authSource + ", tokenExpiresAt = " + tokenExpiresAt + ", accessedOn = " + accessedOn + "]";
    }
}

