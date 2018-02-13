package de.schildbach.wallet.wallofcoins.request;

import com.google.gson.annotations.SerializedName;

/**
 * Created by ABC on 7/26/2017.
 */

public class AddCreateReq {


    /**
     * phone : 9414477260
     * email : demo@gmail.com
     * phoneCode : 1
     * bankBusiness : 1
     * sellCrypto : DASH
     * userEnabled : true
     * dynamicPrice : true
     * primaryMarket : 5
     * secondaryMarket : 4
     * minPayment : 10
     * maxPayment : 1000
     * sellerFee : 23
     * currentPrice : 10
     * name : tap
     * number : 123
     * number2 : 123
     */

    @SerializedName("phone")
    private String phone;
    @SerializedName("email")
    private String email;
    @SerializedName("phoneCode")
    private String phoneCode;
    @SerializedName("bankBusiness")
    private String bankBusiness;
    @SerializedName("sellCrypto")
    private String sellCrypto;
    @SerializedName("userEnabled")
    private boolean userEnabled;
    @SerializedName("dynamicPrice")
    private boolean dynamicPrice;
    @SerializedName("primaryMarket")
    private String primaryMarket;
    @SerializedName("secondaryMarket")
    private String secondaryMarket;
    @SerializedName("minPayment")
    private String minPayment;
    @SerializedName("maxPayment")
    private String maxPayment;
    @SerializedName("sellerFee")
    private String sellerFee;
    @SerializedName("currentPrice")
    private String currentPrice;
    @SerializedName("name")
    private String name;
    @SerializedName("number")
    private String number;
    @SerializedName("number2")
    private String number2;

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

    public String getPhoneCode() {
        return phoneCode;
    }

    public void setPhoneCode(String phoneCode) {
        this.phoneCode = phoneCode;
    }

    public String getBankBusiness() {
        return bankBusiness;
    }

    public void setBankBusiness(String bankBusiness) {
        this.bankBusiness = bankBusiness;
    }

    public String getSellCrypto() {
        return sellCrypto;
    }

    public void setSellCrypto(String sellCrypto) {
        this.sellCrypto = sellCrypto;
    }

    public boolean isUserEnabled() {
        return userEnabled;
    }

    public void setUserEnabled(boolean userEnabled) {
        this.userEnabled = userEnabled;
    }

    public boolean isDynamicPrice() {
        return dynamicPrice;
    }

    public void setDynamicPrice(boolean dynamicPrice) {
        this.dynamicPrice = dynamicPrice;
    }

    public String getPrimaryMarket() {
        return primaryMarket;
    }

    public void setPrimaryMarket(String primaryMarket) {
        this.primaryMarket = primaryMarket;
    }

    public String getSecondaryMarket() {
        return secondaryMarket;
    }

    public void setSecondaryMarket(String secondaryMarket) {
        this.secondaryMarket = secondaryMarket;
    }

    public String getMinPayment() {
        return minPayment;
    }

    public void setMinPayment(String minPayment) {
        this.minPayment = minPayment;
    }

    public String getMaxPayment() {
        return maxPayment;
    }

    public void setMaxPayment(String maxPayment) {
        this.maxPayment = maxPayment;
    }

    public String getSellerFee() {
        return sellerFee;
    }

    public void setSellerFee(String sellerFee) {
        this.sellerFee = sellerFee;
    }

    public String getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(String currentPrice) {
        this.currentPrice = currentPrice;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getNumber2() {
        return number2;
    }

    public void setNumber2(String number2) {
        this.number2 = number2;
    }
}
