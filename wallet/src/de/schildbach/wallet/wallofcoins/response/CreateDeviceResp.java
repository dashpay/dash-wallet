package de.schildbach.wallet.wallofcoins.response;

public class CreateDeviceResp {

    /**
     * id : 4
     * name : New iPhone
     * createdOn : 2015-01-20T17:13:34.154Z
     */

    private int id;
    private String name;
    private String createdOn;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(String createdOn) {
        this.createdOn = createdOn;
    }
}
