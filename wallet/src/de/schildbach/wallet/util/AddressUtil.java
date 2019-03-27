package de.schildbach.wallet.util;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;

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

    public static Address fromBase58(NetworkParameters params, String base58) throws AddressFormatException {
        try {
            return Address.fromBase58(null, base58);
        } catch (AddressFormatException e) {
            if (params == null) {
                return Address.fromBase58(Constants.NETWORK_PARAMETERS, base58);
            } else {
                throw e;
            }
        }
    }
}
