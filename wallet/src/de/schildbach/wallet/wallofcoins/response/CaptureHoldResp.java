package de.schildbach.wallet.wallofcoins.response;

public class CaptureHoldResp {

    /**
     * id : 70
     * total : 51.87391467
     * payment : 504.0000000193
     * paymentDue : 2017-08-09T14:42:46.565Z
     * bankName : MoneyGram
     * nameOnAccount :
     * account : [{"displaySort": 2.0, "name": "birthCountry", "value": "US", "label": "Country of Birth"}, {"displaySort": 0.5, "name": "pickupState", "value": "Florida", "label": "Pick-up State"}, {"displaySort": 1.0, "name": "lastName", "value": "Genito", "label": "Last Name"}, {"displaySort": 0.0, "name": "firstName", "value": "Robert", "label": "First Name"}]
     * status : WD
     * nearestBranch : {"city":"","state":"","name":"MoneyGram","phone":null,"address":""}
     * bankUrl : https://secure.moneygram.com
     * bankLogo : /media/logos/logo_us_MoneyGram.png
     * bankIcon : /media/logos/icon_us_MoneyGram.png
     * bankIconHq : /media/logos/icon_us_MoneyGram%402x.png
     * privateId : 8eca9b5b05b92925ba31cf4e1682d790871acd93
     */

    public int id;
    public String total;
    public String payment;
    public String paymentDue;
    public String bankName;
    public String nameOnAccount;
    public String account;
    public String status;
    public NearestBranchBean nearestBranch;
    public String bankUrl;
    public String bankLogo;
    public String bankIcon;
    public String bankIconHq;
    public String privateId;

    public static class NearestBranchBean {
        /**
         * city :
         * state :
         * name : MoneyGram
         * phone : null
         * address :
         */

        public String city;
        public String state;
        public String name;
        public String phone;
        public String address;
    }
}
