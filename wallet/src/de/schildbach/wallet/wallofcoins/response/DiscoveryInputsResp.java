package de.schildbach.wallet.wallofcoins.response;

public class DiscoveryInputsResp {


    /**
     * id : 2948e301f2dbc2cd52c7284e2ebbd6bd
     * usdAmount : 500
     * cryptoAmount : 0
     * crypto : DASH
     * fiat : USD
     * zipCode : 34236
     * bank : null
     * state : null
     * cryptoAddress : null
     * createdIp : 127.0.0.1
     * location : {"latitude":27.3331293,"longitude":-82.5456374}
     * browserLocation : null
     * publisher : null
     */

    public String id;
    public String usdAmount;
    public String cryptoAmount;
    public String crypto;
    public String fiat;
    public String zipCode;
    public String bank;
    public String state;
    public String cryptoAddress;
    public String createdIp;
    public LocationBean location;
    public String browserLocation;
    public String publisher;

    public static class LocationBean {
        /**
         * latitude : 27.3331293
         * longitude : -82.5456374
         */

        public double latitude;
        public double longitude;
    }
}
