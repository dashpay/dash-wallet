package de.schildbach.wallet.wallofcoins.buying_wizard.models;

/**
 * Created on 08-Mar-18.
 */

public class BuyingWizardAccountJson {

    /**
     * displaySort : 0
     * name : fullName
     * value : Ian Marshall
     * label : Full Name
     */

    private String displaySort;
    private String name;
    private String value;
    private String label;

    public String getDisplaySort() {
        return displaySort;
    }

    public void setDisplaySort(String displaySort) {
        this.displaySort = displaySort;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}