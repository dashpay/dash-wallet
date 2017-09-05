package de.schildbach.wallet.wallofcoins.response;

/**
 * Created by VIJAY on 05-Sep-17.
 */

public class OrderListResp {

    /**
     * id : 85337
     * total : 0.02747620
     * payment : 10.00
     * paymentDue : 2017-09-05T09:31:23.474Z
     * bankName : Fifth Third
     * nameOnAccount : Todd A Rogers
     * account : 7914882944
     * status : WD
     * nearestBranch : {"city":"Sarasota","state":"FL","name":"Fifth Third","phone":"(941) 365-6568","address":"50 Central Ave"}
     * bankUrl : http://www.53.com
     * bankLogo : https://wallofcoins-static.s3.amazonaws.com/logos/logo_18.png
     * bankIcon : https://wallofcoins-static.s3.amazonaws.com/logos/icon_18.png
     * bankIconHq : https://wallofcoins-static.s3.amazonaws.com/logos/icon_18%402x.png
     * privateId : df5a5c438ffea5c36e899858cc33b828e4485a67
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
         * city : Sarasota
         * state : FL
         * name : Fifth Third
         * phone : (941) 365-6568
         * address : 50 Central Ave
         */

        public String city;
        public String state;
        public String name;
        public String phone;
        public String address;
    }
}
