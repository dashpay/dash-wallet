package de.schildbach.wallet.wallofcoins.response;


import com.google.gson.annotations.SerializedName;

public class AdsListActivityResp {


    /**
     * publicBalance : 0E-8
     * secondaryMarket : None
     * verified : false
     * sellCrypto : DASH
     * primaryMarket : None
     * currentPrice : 10.00
     * buyCurrency : USD
     * fundingAddress : (Not Available - Needs Verification)
     * sellerFee : None
     * published : false
     * onHold : 0E-8
     * balance : 0E-8
     * id : 863
     * dynamicPrice : false
     */

    @SerializedName("publicBalance")
    private String publicBalance;
    @SerializedName("secondaryMarket")
    private String secondaryMarket;
    @SerializedName("verified")
    private boolean verified;
    @SerializedName("sellCrypto")
    private String sellCrypto;
    @SerializedName("primaryMarket")
    private String primaryMarket;
    @SerializedName("currentPrice")
    private String currentPrice;
    @SerializedName("buyCurrency")
    private String buyCurrency;
    @SerializedName("fundingAddress")
    private String fundingAddress;
    @SerializedName("sellerFee")
    private String sellerFee;
    @SerializedName("published")
    private boolean published;
    @SerializedName("onHold")
    private String onHold;
    @SerializedName("balance")
    private String balance;
    @SerializedName("id")
    private int id;
    @SerializedName("dynamicPrice")
    private boolean dynamicPrice;

    public String getPublicBalance() {
        return publicBalance;
    }

    public void setPublicBalance(String publicBalance) {
        this.publicBalance = publicBalance;
    }

    public String getSecondaryMarket() {
        return secondaryMarket;
    }

    public void setSecondaryMarket(String secondaryMarket) {
        this.secondaryMarket = secondaryMarket;
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

    public String getPrimaryMarket() {
        return primaryMarket;
    }

    public void setPrimaryMarket(String primaryMarket) {
        this.primaryMarket = primaryMarket;
    }

    public String getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(String currentPrice) {
        this.currentPrice = currentPrice;
    }

    public String getBuyCurrency() {
        return buyCurrency;
    }

    public void setBuyCurrency(String buyCurrency) {
        this.buyCurrency = buyCurrency;
    }

    public String getFundingAddress() {
        return fundingAddress;
    }

    public void setFundingAddress(String fundingAddress) {
        this.fundingAddress = fundingAddress;
    }

    public String getSellerFee() {
        return sellerFee;
    }

    public void setSellerFee(String sellerFee) {
        this.sellerFee = sellerFee;
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }

    public String getOnHold() {
        return onHold;
    }

    public void setOnHold(String onHold) {
        this.onHold = onHold;
    }

    public String getBalance() {
        return balance;
    }

    public void setBalance(String balance) {
        this.balance = balance;
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
}
