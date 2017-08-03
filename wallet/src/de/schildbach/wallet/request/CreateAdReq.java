package de.schildbach.wallet.request;

public class CreateAdReq {

    /**
     * phone : 19033949493
     * email : demo@geni.to
     * phoneCode : 1
     * bankBusiness : 17
     * sellCrypto : DASH
     * userEnabled : true
     * dynamicPrice : true
     * primaryMarket : 4
     * secondaryMarket : 5
     * minPayment : 10
     * maxPayment : 1000
     * sellerFee : 0
     * currentPrice : 10
     * usePayFields : true
     */

    public String phone;
    public String email;
    public String phoneCode;
    public String bankBusiness;
    public String sellCrypto;
    public boolean userEnabled;
    public boolean dynamicPrice;
    public String primaryMarket;
    public String secondaryMarket;
    public String minPayment;
    public String maxPayment;
    public String sellerFee;
    public String currentPrice;
    public boolean usePayFields;
}
