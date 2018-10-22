package org.dash.wallet.integration.uphold.data;

import java.math.BigDecimal;

public class UpholdTransaction {

    private String id;
    private Origin origin;

    public static class Origin {
        private BigDecimal base;
        private BigDecimal amount;
        private BigDecimal fee;

        public BigDecimal getBase() {
            return base;
        }

        public void setBase(BigDecimal base) {
            this.base = base;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public BigDecimal getFee() {
            return fee;
        }

        public void setFee(BigDecimal fee) {
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
