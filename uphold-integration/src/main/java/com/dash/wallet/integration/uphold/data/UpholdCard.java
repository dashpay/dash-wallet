package com.dash.wallet.integration.uphold.data;

import java.util.List;

public class UpholdCard {

    private String id;
    private UpholdCardAddress address;
    private String available;
    private String balance;
    private String currency;
    private String label;
    private String lastTransactionAt;
    private List<UpholdBankAccount> wire;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public UpholdCardAddress getAddress() {
        return address;
    }

    public void setAddress(UpholdCardAddress address) {
        this.address = address;
    }

    public String getAvailable() {
        return available;
    }

    public void setAvailable(String available) {
        this.available = available;
    }

    public String getBalance() {
        return balance;
    }

    public void setBalance(String balance) {
        this.balance = balance;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getLastTransactionAt() {
        return lastTransactionAt;
    }

    public void setLastTransactionAt(String lastTransactionAt) {
        this.lastTransactionAt = lastTransactionAt;
    }

    public List<UpholdBankAccount> getWire() {
        return wire;
    }

    public void setWire(List<UpholdBankAccount> wire) {
        this.wire = wire;
    }

}
