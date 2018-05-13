package org.dash.wallet.integration.uphold.data;

public class UpholdBankAccount {

    private String accountName;
    private UpholdAddress address;
    private String bic;
    private String currency;
    private String iban;
    private String name;

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public UpholdAddress getAddress() {
        return address;
    }

    public void setAddress(UpholdAddress address) {
        this.address = address;
    }

    public String getBic() {
        return bic;
    }

    public void setBic(String bic) {
        this.bic = bic;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getIban() {
        return iban;
    }

    public void setIban(String iban) {
        this.iban = iban;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
