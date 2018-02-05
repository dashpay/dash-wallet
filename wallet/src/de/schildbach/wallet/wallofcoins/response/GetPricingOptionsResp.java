package de.schildbach.wallet.wallofcoins.response;

public class GetPricingOptionsResp {

    /**
     * id : 4
     * domain : exmo.com
     * crypto : DASH
     * fiat : USD
     * url : https://api.exmo.com/v1/ticker/
     * price : 85.53
     * priceUnits : 100.00
     * lastUpdated : 2017-05-01T18:56:05.683Z
     * online : true
     * label : EXMO
     */

    public int id;
    public String domain;
    public String crypto;
    public String fiat;
    public String url;
    public String price;
    public String priceUnits;
    public String lastUpdated;
    public boolean online;
    public String label;
}
