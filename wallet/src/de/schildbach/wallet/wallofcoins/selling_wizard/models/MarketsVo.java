package de.schildbach.wallet.wallofcoins.selling_wizard.models;

/**
 * Created by  on 04-Apr-18.
 */

public class MarketsVo {

    private String id;

    private String price;

    private String domain;

    private String lastUpdated;

    private String priceUnits;

    private String label;

    private String fiat;

    private String crypto;

    private String url;

    private String online;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getPriceUnits() {
        return priceUnits;
    }

    public void setPriceUnits(String priceUnits) {
        this.priceUnits = priceUnits;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getFiat() {
        return fiat;
    }

    public void setFiat(String fiat) {
        this.fiat = fiat;
    }

    public String getCrypto() {
        return crypto;
    }

    public void setCrypto(String crypto) {
        this.crypto = crypto;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getOnline() {
        return online;
    }

    public void setOnline(String online) {
        this.online = online;
    }

    @Override
    public String toString() {
        return "ClassPojo [id = " + id + ", price = " + price + ", domain = " + domain + ", lastUpdated = " + lastUpdated + ", priceUnits = " + priceUnits + ", label = " + label + ", fiat = " + fiat + ", crypto = " + crypto + ", url = " + url + ", online = " + online + "]";
    }
}
