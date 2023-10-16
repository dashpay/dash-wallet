/*
 * Copyright 2015-present the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.integrations.uphold.data;

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
