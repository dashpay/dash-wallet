package de.schildbach.wallet.response;

import com.google.gson.annotations.SerializedName;

/**
 * Created by ABC on 7/26/2017.
 */

public class AddCreateReponse {

    /**
     * createdIp : 127.0.0.1
     * userEnabled : true
     * account_id : 32
     * primaryMarket : null
     * minPayment : 0
     * currentPrice : 10
     * totalReceived : 0
     * id : 25
     * dynamicPrice : false
     * publicBalance : 0
     * secondaryMarket : null
     * fee : null
     * verified : false
     * sellCrypto : DASH
     * success : true
     * maxPayment : 0
     * buyCurrency : USD
     * published : false
     * onHold : 0
     */

    @SerializedName("createdIp")
    private String createdIp;
    @SerializedName("userEnabled")
    private boolean userEnabled;
    @SerializedName("account_id")
    private int accountId;
    @SerializedName("primaryMarket")
    private Object primaryMarket;
    @SerializedName("minPayment")
    private int minPayment;
    @SerializedName("currentPrice")
    private int currentPrice;
    @SerializedName("totalReceived")
    private int totalReceived;
    @SerializedName("id")
    private int id;
    @SerializedName("dynamicPrice")
    private boolean dynamicPrice;
    @SerializedName("publicBalance")
    private int publicBalance;
    @SerializedName("secondaryMarket")
    private Object secondaryMarket;
    @SerializedName("fee")
    private Object fee;
    @SerializedName("verified")
    private boolean verified;
    @SerializedName("sellCrypto")
    private String sellCrypto;
    @SerializedName("success")
    private boolean success;
    @SerializedName("maxPayment")
    private int maxPayment;
    @SerializedName("buyCurrency")
    private String buyCurrency;
    @SerializedName("published")
    private boolean published;
    @SerializedName("onHold")
    private int onHold;

    public String getCreatedIp() {
        return createdIp;
    }

    public void setCreatedIp(String createdIp) {
        this.createdIp = createdIp;
    }

    public boolean isUserEnabled() {
        return userEnabled;
    }

    public void setUserEnabled(boolean userEnabled) {
        this.userEnabled = userEnabled;
    }

    public int getAccountId() {
        return accountId;
    }

    public void setAccountId(int accountId) {
        this.accountId = accountId;
    }

    public Object getPrimaryMarket() {
        return primaryMarket;
    }

    public void setPrimaryMarket(Object primaryMarket) {
        this.primaryMarket = primaryMarket;
    }

    public int getMinPayment() {
        return minPayment;
    }

    public void setMinPayment(int minPayment) {
        this.minPayment = minPayment;
    }

    public int getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(int currentPrice) {
        this.currentPrice = currentPrice;
    }

    public int getTotalReceived() {
        return totalReceived;
    }

    public void setTotalReceived(int totalReceived) {
        this.totalReceived = totalReceived;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public boolean isDynamicPrice() {
        return dynamicPrice;
    }

    public void setDynamicPrice(boolean dynamicPrice) {
        this.dynamicPrice = dynamicPrice;
    }

    public int getPublicBalance() {
        return publicBalance;
    }

    public void setPublicBalance(int publicBalance) {
        this.publicBalance = publicBalance;
    }

    public Object getSecondaryMarket() {
        return secondaryMarket;
    }

    public void setSecondaryMarket(Object secondaryMarket) {
        this.secondaryMarket = secondaryMarket;
    }

    public Object getFee() {
        return fee;
    }

    public void setFee(Object fee) {
        this.fee = fee;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public String getSellCrypto() {
        return sellCrypto;
    }

    public void setSellCrypto(String sellCrypto) {
        this.sellCrypto = sellCrypto;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getMaxPayment() {
        return maxPayment;
    }

    public void setMaxPayment(int maxPayment) {
        this.maxPayment = maxPayment;
    }

    public String getBuyCurrency() {
        return buyCurrency;
    }

    public void setBuyCurrency(String buyCurrency) {
        this.buyCurrency = buyCurrency;
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }

    public int getOnHold() {
        return onHold;
    }

    public void setOnHold(int onHold) {
        this.onHold = onHold;
    }
}
