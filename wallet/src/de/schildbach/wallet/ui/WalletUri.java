/*
 * Copyright 2013-2015 the original author or authors.
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

package de.schildbach.wallet.ui;

import android.content.Intent;
import android.net.Uri;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.uri.BitcoinURIParseException;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.util.AddressUtil;

public class WalletUri {

    public static final String FIELD_PAY = "pay";
    public static final String FIELD_AMOUNT = "amount";
    public static final String FIELD_INSTANT_SEND = "IS";
    public static final String FIELD_FORCE_INSTANT_SEND = "req-IS";
    public static final String FIELD_SENDER = "sender";
    public static final String FIELD_SOURCE = "source";
    public static final String FIELD_ADDRESS = "address";
    public static final String FIELD_CALLBACK = "callback";
    public static final String FIELD_TXID = "txid";
    public static final String FIELD_MASTER_PUBLIC_KEY_BIP32 = "masterPublicKeyBIP32";
    public static final String FIELD_MASTER_PUBLIC_KEY_BIP44 = "masterPublicKeyBIP44";
    public static final String FIELD_REQUEST = "request";
    public static final String FIELD_ACCOUNT = "account";

    public static final String REQUEST_MASTER_PUBLIC_KEY = "masterPublicKey";
    public static final String REQUEST_ADDRESS = "address";

    public static final String CALLBACK_PAYACK = "payack";

    public static final String SCHEME = Constants.WALLET_URI_SCHEME;

    private Uri sourceUri;

    private WalletUri(Uri sourceUri) {
        this.sourceUri = sourceUri;
    }

    public static WalletUri parse(Uri input) throws BitcoinURIParseException {
        input = normalizeUri(input);
        String missingParameterFormat = "Missing %s parameter";
        if (!SCHEME.equals(input.getScheme())) {
            throw new BitcoinURIParseException("Incorrect scheme " + input.getScheme());
        }
        if (input.getQueryParameter(FIELD_PAY) != null) {
            if (input.getQueryParameter(FIELD_AMOUNT) == null) {
                throw new BitcoinURIParseException(String.format(missingParameterFormat, FIELD_AMOUNT));
            }
            String rawAddress = input.getQueryParameter(FIELD_PAY);
            Address address;
            try {
                address = AddressUtil.fromString(null, rawAddress);
            } catch (Exception e) {
                throw new BitcoinURIParseException(e.getMessage());
            }
            if (!Constants.NETWORK_PARAMETERS.equals(address.getParameters())) {
                throw new BitcoinURIParseException("Mismatched network");
            }
        } else {
            String request = input.getQueryParameter(FIELD_REQUEST);
            if (!REQUEST_MASTER_PUBLIC_KEY.equals(request) && !REQUEST_ADDRESS.equals(request)) {
                throw new BitcoinURIParseException("Unsupported request " + request);
            }
            if (input.getQueryParameter(FIELD_SENDER) == null) {
                throw new BitcoinURIParseException(String.format(missingParameterFormat, FIELD_SENDER));
            }
        }
        return new WalletUri(input);
    }

    /**
     * Normalize Uri by adding missing question mark in front of query parameters so that it could be correctly handled by Uri class
     * Converts {scheme}://{query} to {scheme}://?{query}
     *
     * @param walletUri uri to be normalized
     * @return normalized Uri of walletUri if it was already correct
     */
    private static Uri normalizeUri(Uri walletUri) {
        String schemeSpecificPart = walletUri.getSchemeSpecificPart();
        if (schemeSpecificPart.startsWith("//") && !schemeSpecificPart.startsWith("//?")) {
            String normalizedUriStr = walletUri.toString().replace("//", "//?");
            return Uri.parse(normalizedUriStr);
        } else {
            return walletUri;
        }
    }

    public boolean isPaymentUri() {
        return (getPayAddress() != null) && (getAmount() != null);
    }

    public boolean isMasterPublicKeyRequest() {
        return REQUEST_MASTER_PUBLIC_KEY.equals(getRequest());
    }

    public boolean isAddressRequest() {
        return REQUEST_ADDRESS.equals(getRequest());
    }

    public Address getPayAddress() {
        String pay = sourceUri.getQueryParameter(FIELD_PAY);
        return (pay != null) ? AddressUtil.fromString(null, pay) : null;
    }

    public Coin getAmount() {
        String amount = sourceUri.getQueryParameter(FIELD_AMOUNT);
        return (amount != null) ? Coin.parseCoin(amount) : null;
    }

    public String getSender() {
        return sourceUri.getQueryParameter(FIELD_SENDER);
    }

    public boolean forceInstantSend() {
        return sourceUri.getBooleanQueryParameter(FIELD_FORCE_INSTANT_SEND, false);
    }

    public boolean useInstantSend() {
        boolean useInstantSend = sourceUri.getBooleanQueryParameter(FIELD_INSTANT_SEND, false);
        return useInstantSend || forceInstantSend();
    }

    public String getRequest() {
        return sourceUri.getQueryParameter(FIELD_REQUEST);
    }

    public BitcoinURI toBitcoinUri() throws BitcoinURIParseException {
        if (!isPaymentUri()) {
            throw new IllegalStateException("Only payment Uri can be converted into BitcoinURI");
        }
        final Address address = getPayAddress();
        Coin amount = getAmount();
        String sender = getSender();
        String bitcoinUri = BitcoinURI.convertToBitcoinURI(address, amount, sender, null);
        if (useInstantSend() || forceInstantSend()) {
            bitcoinUri += "&" + BitcoinURI.FIELD_INSTANTSEND + "=1";
        }
        return new BitcoinURI(bitcoinUri);
    }

    public static Intent createPaymentResult(Uri inputUri, String hash) {
        try {
            WalletUri walletUri = WalletUri.parse(inputUri);
            Intent resultIntent = new Intent();
            Uri data = new Uri.Builder()
                    .authority("")
                    .scheme(walletUri.getSender())
                    .appendQueryParameter(FIELD_CALLBACK, CALLBACK_PAYACK)
                    .appendQueryParameter(FIELD_ADDRESS, walletUri.getPayAddress().toString())
                    .appendQueryParameter(FIELD_TXID, hash)
                    .build();
            resultIntent.setData(data);
            return resultIntent;
        } catch (BitcoinURIParseException e) {
            return null;
        }
    }

    public static Intent createMasterPublicKeyResult(Uri inputUri, String masterPublicKeyBip32,
                                                     String masterPublicKeyBip44, String source) {
        try {
            WalletUri walletUri = WalletUri.parse(inputUri);
            Intent resultIntent = new Intent();
            Uri data = new Uri.Builder()
                    .authority("")
                    .scheme(walletUri.getSender())
                    .appendQueryParameter(FIELD_CALLBACK, REQUEST_MASTER_PUBLIC_KEY)
                    .appendQueryParameter(FIELD_MASTER_PUBLIC_KEY_BIP32, masterPublicKeyBip32)
                    .appendQueryParameter(FIELD_MASTER_PUBLIC_KEY_BIP44, masterPublicKeyBip44)
                    .appendQueryParameter(FIELD_SOURCE, source)
                    .build();
            resultIntent.setData(data);
            return resultIntent;
        } catch (BitcoinURIParseException e) {
            return null;
        }
    }

    public static Intent createAddressResult(Uri inputUri, String address, String source) {
        try {
            WalletUri walletUri = WalletUri.parse(inputUri);
            Intent resultIntent = new Intent();
            Uri data = new Uri.Builder()
                    .authority("")
                    .scheme(walletUri.getSender())
                    .appendQueryParameter(FIELD_CALLBACK, REQUEST_ADDRESS)
                    .appendQueryParameter(FIELD_ADDRESS, address)
                    .appendQueryParameter(FIELD_SOURCE, source)
                    .build();
            resultIntent.setData(data);
            return resultIntent;
        } catch (BitcoinURIParseException e) {
            return null;
        }
    }
}
