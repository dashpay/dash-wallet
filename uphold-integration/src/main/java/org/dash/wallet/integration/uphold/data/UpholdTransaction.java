package org.dash.wallet.integration.uphold.data;

public class UpholdTransaction {

    private String id;
    private Origin origin;

    public static class Origin {
        private Float base;
        private Float amount;
        private Float fee;

        public Float getBase() {
            return base;
        }

        public void setBase(Float base) {
            this.base = base;
        }

        public Float getAmount() {
            return amount;
        }

        public void setAmount(Float amount) {
            this.amount = amount;
        }

        public Float getFee() {
            return fee;
        }

        public void setFee(Float fee) {
            this.fee = fee;
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
