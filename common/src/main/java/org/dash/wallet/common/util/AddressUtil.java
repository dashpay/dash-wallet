/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.util;

import org.dash.wallet.common.payments.parsers.AddressFormatException;
import org.dash.wallet.common.payments.parsers.AddressNetwork;
import org.dash.wallet.common.payments.parsers.AddressUtils;
import org.dash.wallet.common.payments.parsers.PaymentURI;

/**
 * Dashj-free port of the previous bitcoinj-typed helpers: addresses are base58 strings and
 * networks are {@link AddressNetwork} descriptors. Resolution rules are identical — a testnet
 * address is re-interpreted on the current network (testnet and devnets share version bytes).
 */
public class AddressUtil {

    public static AddressNetwork getParametersFromAddress(String address, AddressNetwork currentNetwork)
            throws AddressFormatException {
        AddressNetwork network = AddressNetwork.fromDashAddress(address);
        if (network.getId().equals(AddressNetwork.ID_TESTNET)) {
            return currentNetwork;
        } else {
            return network;
        }
    }

    /** Validates the given base58 address for {@code params} (or the address-derived network when null). */
    public static String fromString(AddressNetwork params, String base58, AddressNetwork currentNetwork)
            throws AddressFormatException {
        AddressNetwork network = (params != null) ? params : getParametersFromAddress(base58, currentNetwork);
        AddressUtils.DecodedAddress decoded = AddressUtils.decode(base58);
        if (!network.acceptsVersion(decoded.getVersion())) {
            throw new AddressFormatException.WrongNetwork(decoded.getVersion());
        }
        return base58;
    }

    /**
     * The address of the payment URI, re-validated against the current network when it decodes
     * as a testnet/devnet address. Mirrors the previous bitcoinj-typed behavior exactly.
     */
    public static String getCorrectAddress(PaymentURI paymentUri, AddressNetwork currentNetwork) {
        String address = paymentUri.getAddress();
        if (address != null) {
            try {
                AddressNetwork network = AddressNetwork.fromDashAddress(address);
                if (network.getId().equals(AddressNetwork.ID_TESTNET)
                        && !currentNetwork.getId().equals(AddressNetwork.ID_TESTNET)) {
                    AddressUtils.DecodedAddress decoded = AddressUtils.decode(address);
                    if (!currentNetwork.acceptsVersion(decoded.getVersion())) {
                        return address; // WrongNetwork: keep the original, like the dashj original
                    }
                }
            } catch (AddressFormatException x) {
                return address;
            }
        }
        return address;
    }
}
