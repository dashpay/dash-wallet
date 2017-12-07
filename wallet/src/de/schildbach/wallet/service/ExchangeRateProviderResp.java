package de.schildbach.wallet.service;

public class ExchangeRateProviderResp {

    /**
     * id : 3
     * domain : bitcoinaverage.com
     * crypto : BTC
     * fiat : USD
     * url : https://api.bitcoinaverage.com/ticker/USD/
     * price : 2502.87
     * priceUnits : 100.00
     * lastUpdated : 2017-12-07T08:26:38.910Z
     * online : false
     * label : Bitcoin Average
     */

    private int id;
    private String domain;
    private String crypto;
    private String fiat;
    private String url;
    private String price;
    private String priceUnits;
    private String lastUpdated;
    private boolean online;
    private String label;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getCrypto() {
        return crypto;
    }

    public void setCrypto(String crypto) {
        this.crypto = crypto;
    }

    public String getFiat() {
        return fiat;
    }

    public void setFiat(String fiat) {
        this.fiat = fiat;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    public String getPriceUnits() {
        return priceUnits;
    }

    public void setPriceUnits(String priceUnits) {
        this.priceUnits = priceUnits;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
