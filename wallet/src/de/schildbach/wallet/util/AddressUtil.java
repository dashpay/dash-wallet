package de.schildbach.wallet.util;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.uri.BitcoinURI;

import de.schildbach.wallet.Constants;

public class AddressUtil {

    public static NetworkParameters getParametersFromAddress(String address) throws AddressFormatException {
        NetworkParameters networkParameters = Address.getParametersFromAddress(address);
        if (networkParameters.equals(TestNet3Params.get())) {
            return Constants.NETWORK_PARAMETERS;
        } else {
            return networkParameters;
        }
    }

    public static Address fromString(NetworkParameters params, String base58) throws AddressFormatException {
        NetworkParameters networkParameters = (params != null) ? params : getParametersFromAddress(base58);
        return Address.fromString(networkParameters, base58);
    }

    public static Address getCorrectAddress(BitcoinURI bitcoinUri) {
        Address address = bitcoinUri.getAddress();
        if (address != null) {
            NetworkParameters networkParameters = address.getParameters();
            if (networkParameters.equals(TestNet3Params.get()) && !Constants.NETWORK_PARAMETERS.equals(TestNet3Params.get())) {
                try {
                    return Address.fromString(Constants.NETWORK_PARAMETERS, address.toString());
                } catch (AddressFormatException.WrongNetwork x) {
                    return address;
                }
            }
        }
        return address;
    }
}
