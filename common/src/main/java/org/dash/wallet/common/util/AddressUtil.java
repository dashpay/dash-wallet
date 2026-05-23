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

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.uri.BitcoinURI;

public class AddressUtil {

    public static NetworkParameters getParametersFromAddress(String address, NetworkParameters currentNetworkParameters) throws AddressFormatException {
        NetworkParameters networkParameters = Address.getParametersFromAddress(address);
        if (networkParameters.equals(TestNet3Params.get())) {
            return currentNetworkParameters;
        } else {
            return networkParameters;
        }
    }

    public static Address fromString(NetworkParameters params, String base58, NetworkParameters currentNetworkParameters) throws AddressFormatException {
        NetworkParameters networkParameters = (params != null) ? params : getParametersFromAddress(base58, currentNetworkParameters);
        return Address.fromString(networkParameters, base58);
    }

    public static Address getCorrectAddress(BitcoinURI bitcoinUri, NetworkParameters currentNetworkParameters) {
        Address address = bitcoinUri.getAddress();
        if (address != null) {
            NetworkParameters networkParameters = address.getParameters();
            if (networkParameters.equals(TestNet3Params.get()) && !currentNetworkParameters.equals(TestNet3Params.get())) {
                try {
                    return Address.fromString(currentNetworkParameters, address.toString());
                } catch (AddressFormatException.WrongNetwork x) {
                    return address;
                }
            }
        }
        return address;
    }
}
