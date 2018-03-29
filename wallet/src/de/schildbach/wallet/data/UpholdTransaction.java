package de.schildbach.wallet.data;

public class UpholdTransaction {

    private String id;
    private Origin origin;

    public static class Origin {
        private String base;
        private String amount;
        private String fee;

        public String getBase() {
            return base;
        }

        public String getAmount() {
            return amount;
        }

        public String getFee() {
            return fee;
        }
    }

    public Origin getOrigin() {
        return origin;
    }

    public void setOrigin(Origin origin) {
        this.origin = origin;
    }

    public String getId() {
        return id;
    }
}
