package de.schildbach.wallet.wallofcoins.selling_wizard.models;

import java.io.Serializable;

/**
 * Created by  on 04-Apr-18.
 */

public class AddressVo implements Serializable {

    //for create add
    private String number;
    private String number2;
    private String phone;
    private String email;
    private String phoneCode;
    private String bankBusiness;
    private String sellerFee;
    private String name;

    //for response add
    private String sellCrypto;

    private String account_id;

    private String currentPrice;

    private String totalReceived;

    private String buyCurrency;

    private String id;

    private String fee;

    private String minPayment;

    private boolean dynamicPrice;

    private String createdIp;

    private String secondaryMarket;

    private String verified;

    private String maxPayment;

    private String publicBalance;

    private boolean userEnabled;

    private String published;

    private String success;

    private String primaryMarket;

    private String onHold;

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

    public String getSellerFee() {
        return sellerFee;
    }

    public void setSellerFee(String sellerFee) {
        this.sellerFee = sellerFee;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }


    public String getSellCrypto() {
        return sellCrypto;
    }

    public void setSellCrypto(String sellCrypto) {
        this.sellCrypto = sellCrypto;
    }

    public String getAccount_id() {
        return account_id;
    }

    public void setAccount_id(String account_id) {
        this.account_id = account_id;
    }

    public String getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(String currentPrice) {
        this.currentPrice = currentPrice;
    }

    public String getTotalReceived() {
        return totalReceived;
    }

    public void setTotalReceived(String totalReceived) {
        this.totalReceived = totalReceived;
    }

    public String getBuyCurrency() {
        return buyCurrency;
    }

    public void setBuyCurrency(String buyCurrency) {
        this.buyCurrency = buyCurrency;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFee() {
        return fee;
    }

    public void setFee(String fee) {
        this.fee = fee;
    }

    public String getMinPayment() {
        return minPayment;
    }

    public void setMinPayment(String minPayment) {
        this.minPayment = minPayment;
    }

    public boolean getDynamicPrice() {
        return dynamicPrice;
    }

    public void setDynamicPrice(boolean dynamicPrice) {
        this.dynamicPrice = dynamicPrice;
    }

    public String getCreatedIp() {
        return createdIp;
    }

    public void setCreatedIp(String createdIp) {
        this.createdIp = createdIp;
    }

    public String getSecondaryMarket() {
        return secondaryMarket;
    }

    public void setSecondaryMarket(String secondaryMarket) {
        this.secondaryMarket = secondaryMarket;
    }

    public String getVerified() {
        return verified;
    }

    public void setVerified(String verified) {
        this.verified = verified;
    }

    public String getMaxPayment() {
        return maxPayment;
    }

    public void setMaxPayment(String maxPayment) {
        this.maxPayment = maxPayment;
    }

    public String getPublicBalance() {
        return publicBalance;
    }

    public void setPublicBalance(String publicBalance) {
        this.publicBalance = publicBalance;
    }

    public boolean getUserEnabled() {
        return userEnabled;
    }

    public void setUserEnabled(boolean userEnabled) {
        this.userEnabled = userEnabled;
    }

    public String getPublished() {
        return published;
    }

    public void setPublished(String published) {
        this.published = published;
    }

    public String getSuccess() {
        return success;
    }

    public void setSuccess(String success) {
        this.success = success;
    }

    public String getPrimaryMarket() {
        return primaryMarket;
    }

    public void setPrimaryMarket(String primaryMarket) {
        this.primaryMarket = primaryMarket;
    }

    public String getOnHold() {
        return onHold;
    }

    public void setOnHold(String onHold) {
        this.onHold = onHold;
    }

    @Override
    public String toString() {
        return "ClassPojo [sellCrypto = " + sellCrypto + ", account_id = " + account_id + ", currentPrice = " + currentPrice + ", totalReceived = " + totalReceived + ", buyCurrency = " + buyCurrency + ", id = " + id + ", fee = " + fee + ", minPayment = " + minPayment + ", dynamicPrice = " + dynamicPrice + ", createdIp = " + createdIp + ", secondaryMarket = " + secondaryMarket + ", verified = " + verified + ", maxPayment = " + maxPayment + ", publicBalance = " + publicBalance + ", userEnabled = " + userEnabled + ", published = " + published + ", success = " + success + ", primaryMarket = " + primaryMarket + ", onHold = " + onHold + "]";
    }
}
