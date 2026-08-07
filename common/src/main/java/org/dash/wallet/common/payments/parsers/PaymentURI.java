/*
 * Copyright 2012, 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.common.payments.parsers;

import androidx.annotation.Nullable;

import org.dash.wallet.common.money.Coin;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Provides a standard implementation of a Dash URI with support for the following:
 *
 * <ul>
 * <li>URLEncoded URIs (as passed in by IE on the command line)</li>
 * <li>BIP21 names (including the "req-" prefix handling requirements)</li>
 * </ul>
 *
 * <p>Self-contained port of dashj's {@code org.bitcoinj.uri.BitcoinURI} (22.0.3, including the
 * dashj-specific {@code user} field) so that modules depending on {@code common} need no dashj on
 * their classpath. Accepted URIs, produced URIs and error messages are identical; addresses are
 * held as validated base58/bech32 strings.</p>
 */
public class PaymentURI {
    /**
     * Thrown when the URI cannot be parsed. Mirrors {@code org.bitcoinj.uri.BitcoinURIParseException}.
     */
    public static class ParseException extends Exception {
        public ParseException(String s) {
            super(s);
        }

        public ParseException(String s, Throwable throwable) {
            super(s, throwable);
        }
    }

    /** Mirrors {@code org.bitcoinj.uri.OptionalFieldValidationException}. */
    public static class OptionalFieldValidationException extends ParseException {
        public OptionalFieldValidationException(String s) {
            super(s);
        }

        public OptionalFieldValidationException(String s, Throwable throwable) {
            super(s, throwable);
        }
    }

    /** Mirrors {@code org.bitcoinj.uri.RequiredFieldValidationException}. */
    public static class RequiredFieldValidationException extends ParseException {
        public RequiredFieldValidationException(String s) {
            super(s);
        }

        public RequiredFieldValidationException(String s, Throwable throwable) {
            super(s, throwable);
        }
    }

    public static final String FIELD_MESSAGE = "message";
    public static final String FIELD_LABEL = "label";
    public static final String FIELD_AMOUNT = "amount";
    public static final String FIELD_ADDRESS = "address";
    public static final String FIELD_PAYMENT_REQUEST_URL = "r";
    public static final String FIELD_USER = "user";

    public static final String DASH_SCHEME = "dash";
    private static final String ENCODED_SPACE_CHARACTER = "%20";
    private static final String AMPERSAND_SEPARATOR = "&";
    private static final String QUESTION_MARK_SEPARATOR = "?";

    /**
     * Contains all the parameters in the order in which they appeared.
     */
    private final Map<String, Object> parameterMap = new LinkedHashMap<>();

    /**
     * Constructs a new PaymentURI from the given string. Can be for any network. The address is
     * validated against whichever Dash network its version byte belongs to.
     *
     * @param uri The raw URI data to be parsed (see class comments for accepted formats)
     * @throws ParseException if the URI is not syntactically or semantically valid.
     */
    public PaymentURI(String uri) throws ParseException {
        this(null, uri);
    }

    /**
     * Constructs a new object by trying to parse the input as a valid payment URI.
     *
     * @param network The network the URI is from, or null if you don't have any expectation about what network the URI
     *                is for and wish to check yourself.
     * @param input   The raw URI data to be parsed (see class comments for accepted formats)
     * @throws ParseException If the input fails payment URI syntax and semantic checks.
     */
    public PaymentURI(@Nullable AddressNetwork network, String input) throws ParseException {
        checkNotNull(input);

        String scheme = null == network ? DASH_SCHEME : network.getUriScheme();

        // Attempt to form the URI (fail fast syntax checking to official standards).
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException e) {
            throw new ParseException("Bad URI syntax", e);
        }

        // URI is formed as  dash:<address>?<query parameters>
        // blockchain.info generates URIs of non-BIP compliant form dash://address?....
        String blockchainInfoScheme = scheme + "://";
        String correctScheme = scheme + ":";
        String schemeSpecificPart;
        if (input.toLowerCase(Locale.US).startsWith(blockchainInfoScheme)) {
            schemeSpecificPart = input.substring(blockchainInfoScheme.length());
        } else if (input.toLowerCase(Locale.US).startsWith(correctScheme)) {
            schemeSpecificPart = input.substring(correctScheme.length());
        } else {
            throw new ParseException("Unsupported URI scheme: " + uri.getScheme());
        }

        // Split off the address from the rest of the query parameters.
        String[] addressSplitTokens = schemeSpecificPart.split("\\?", 2);
        if (addressSplitTokens.length == 0)
            throw new ParseException("No data found after the dash: prefix");
        String addressToken = addressSplitTokens[0];  // may be empty!

        String[] nameValuePairTokens;
        if (addressSplitTokens.length == 1) {
            // Only an address is specified without any additional parameters.
            nameValuePairTokens = new String[]{};
        } else {
            // Split into '<name>=<value>' tokens.
            nameValuePairTokens = addressSplitTokens[1].split("&");
        }

        // Attempt to parse the rest of the URI parameters.
        parseParameters(network, addressToken, nameValuePairTokens);

        if (!addressToken.isEmpty()) {
            // Attempt to parse the addressToken as a base58 address for this network
            // (mirrors Address.fromBase58 — segwit addresses are not accepted here).
            try {
                if (network != null) {
                    AddressUtils.DecodedAddress decoded = AddressUtils.decode(addressToken);
                    if (!network.acceptsVersion(decoded.getVersion())) {
                        throw new AddressFormatException.WrongNetwork(decoded.getVersion());
                    }
                } else {
                    AddressNetwork.fromDashAddress(addressToken);
                }
                putWithValidation(FIELD_ADDRESS, addressToken);
            } catch (AddressFormatException e) {
                throw new ParseException("Bad address", e);
            }
        }

        if (addressToken.isEmpty() && getPaymentRequestUrl() == null) {
            throw new ParseException("No address and no r= parameter found");
        }
    }

    /**
     * @param network             The network the URI is from
     * @param nameValuePairTokens The tokens representing the name value pairs (assumed to be
     *                            separated by '=' e.g. 'amount=0.2')
     */
    private void parseParameters(@Nullable AddressNetwork network, String addressToken,
                                 String[] nameValuePairTokens) throws ParseException {
        // Attempt to decode the rest of the tokens into a parameter map.
        for (String nameValuePairToken : nameValuePairTokens) {
            final int sepIndex = nameValuePairToken.indexOf('=');
            if (sepIndex == -1)
                throw new ParseException("Malformed Dash URI - no separator in '" + nameValuePairToken + "'");
            if (sepIndex == 0)
                throw new ParseException("Malformed Dash URI - empty name '" + nameValuePairToken + "'");
            final String nameToken = nameValuePairToken.substring(0, sepIndex).toLowerCase(Locale.ENGLISH);
            final String valueToken = nameValuePairToken.substring(sepIndex + 1);

            // Parse the amount.
            if (FIELD_AMOUNT.equals(nameToken)) {
                // Decode the amount (contains an optional decimal component to 8dp).
                try {
                    Coin amount = Coin.parseCoin(valueToken);
                    if (network != null && amount.isGreaterThan(Coin.valueOf(network.getMaxMoney())))
                        throw new ParseException("Max number of coins exceeded");
                    if (amount.signum() < 0)
                        throw new ArithmeticException("Negative coins specified");
                    putWithValidation(FIELD_AMOUNT, amount);
                } catch (IllegalArgumentException e) {
                    throw new OptionalFieldValidationException(
                            String.format(Locale.US, "'%s' is not a valid amount", valueToken), e);
                } catch (ArithmeticException e) {
                    throw new OptionalFieldValidationException(
                            String.format(Locale.US, "'%s' has too many decimal places", valueToken), e);
                }
            } else {
                if (nameToken.startsWith("req-")) {
                    // A required parameter that we do not know about.
                    throw new RequiredFieldValidationException(
                            "'" + nameToken + "' is required but not known, this URI is not valid");
                } else {
                    // Known fields and unknown parameters that are optional.
                    try {
                        if (valueToken.length() > 0)
                            putWithValidation(nameToken, URLDecoder.decode(valueToken, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        // Unreachable.
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        // Note to the future: when you want to implement 'req-expires' have a look at commit 410a53791841
        // which had it in.
    }

    /**
     * Put the value against the key in the map checking for duplication. This avoids address field overwrite etc.
     *
     * @param key   The key for the map
     * @param value The value to store
     */
    private void putWithValidation(String key, Object value) throws ParseException {
        if (parameterMap.containsKey(key)) {
            throw new ParseException(String.format(Locale.US, "'%s' is duplicated, URI is invalid", key));
        } else {
            parameterMap.put(key, value);
        }
    }

    /**
     * The base58/bech32 address from the URI, if one was present. It's possible to have Dash URI's
     * with no address if a r= payment protocol parameter is specified, though this form is not recommended as older
     * wallets can't understand it.
     */
    @Nullable
    public String getAddress() {
        return (String) parameterMap.get(FIELD_ADDRESS);
    }

    /**
     * @return The amount name encoded using a pure integer value based at
     * 10,000,000 units is 1 DASH. May be null if no amount is specified
     */
    @Nullable
    public Coin getAmount() {
        return (Coin) parameterMap.get(FIELD_AMOUNT);
    }

    /**
     * @return The label from the URI.
     */
    @Nullable
    public String getLabel() {
        return (String) parameterMap.get(FIELD_LABEL);
    }

    /**
     * @return The message from the URI.
     */
    @Nullable
    public String getMessage() {
        return (String) parameterMap.get(FIELD_MESSAGE);
    }

    /**
     * @return The user from the URI (dashj addition).
     */
    @Nullable
    public String getUser() {
        return (String) parameterMap.get(FIELD_USER);
    }

    /**
     * @return The URL where a payment request (as specified in BIP 70) may be fetched.
     */
    @Nullable
    public final String getPaymentRequestUrl() {
        return (String) parameterMap.get(FIELD_PAYMENT_REQUEST_URL);
    }

    /**
     * Returns the URLs where a payment request (as specified in BIP 70) may be fetched. The first URL is the main URL,
     * all subsequent URLs are fallbacks.
     */
    public List<String> getPaymentRequestUrls() {
        ArrayList<String> urls = new ArrayList<>();
        while (true) {
            int i = urls.size();
            String paramName = FIELD_PAYMENT_REQUEST_URL + (i > 0 ? Integer.toString(i) : "");
            String url = (String) parameterMap.get(paramName);
            if (url == null)
                break;
            urls.add(url);
        }
        java.util.Collections.reverse(urls);
        return urls;
    }

    /**
     * @param name The name of the parameter
     * @return The parameter value, or null if not present
     */
    @Nullable
    public Object getParameterByName(String name) {
        return parameterMap.get(name);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("DashURI[");
        boolean first = true;
        for (Map.Entry<String, Object> entry : parameterMap.entrySet()) {
            if (first) {
                first = false;
            } else {
                builder.append(",");
            }
            builder.append("'").append(entry.getKey()).append("'=").append("'").append(entry.getValue()).append("'");
        }
        builder.append("]");
        return builder.toString();
    }

    /**
     * Simple payment URI builder using known good fields.
     *
     * @param network The network the address is for.
     * @param address The base58/bech32 address.
     * @param amount  The amount.
     * @param label   A label.
     * @param message A message.
     * @return A String containing the payment URI.
     */
    public static String convertToPaymentURI(AddressNetwork network, String address, @Nullable Coin amount,
                                             @Nullable String label, @Nullable String message) {
        return convertToPaymentURI(network, address, amount, label, message, null);
    }

    /**
     * Simple payment URI builder using known good fields.
     *
     * @param network The network the address is for.
     * @param address The base58/bech32 address.
     * @param amount  The amount.
     * @param label   A label.
     * @param message A message.
     * @param user    A DashPay user (dashj addition).
     * @return A String containing the payment URI.
     */
    public static String convertToPaymentURI(AddressNetwork network, String address, @Nullable Coin amount,
                                             @Nullable String label, @Nullable String message, @Nullable String user) {
        checkNotNull(network);
        checkNotNull(address);
        if (amount != null && amount.signum() < 0) {
            throw new IllegalArgumentException("Coin must be positive");
        }

        StringBuilder builder = new StringBuilder();
        String scheme = network.getUriScheme();
        builder.append(scheme).append(":").append(address);

        boolean questionMarkHasBeenOutput = false;

        if (amount != null) {
            builder.append(QUESTION_MARK_SEPARATOR).append(FIELD_AMOUNT).append("=");
            builder.append(amount.toPlainString());
            questionMarkHasBeenOutput = true;
        }

        if (label != null && !"".equals(label)) {
            if (questionMarkHasBeenOutput) {
                builder.append(AMPERSAND_SEPARATOR);
            } else {
                builder.append(QUESTION_MARK_SEPARATOR);
                questionMarkHasBeenOutput = true;
            }
            builder.append(FIELD_LABEL).append("=").append(encodeURLString(label));
        }

        if (message != null && !"".equals(message)) {
            if (questionMarkHasBeenOutput) {
                builder.append(AMPERSAND_SEPARATOR);
            } else {
                builder.append(QUESTION_MARK_SEPARATOR);
                questionMarkHasBeenOutput = true;
            }
            builder.append(FIELD_MESSAGE).append("=").append(encodeURLString(message));
        }

        if (user != null && !"".equals(user)) {
            if (questionMarkHasBeenOutput) {
                builder.append(AMPERSAND_SEPARATOR);
            } else {
                builder.append(QUESTION_MARK_SEPARATOR);
            }
            builder.append(FIELD_USER).append("=").append(encodeURLString(user));
        }

        return builder.toString();
    }

    /**
     * Encode a string using URL encoding
     *
     * @param stringToEncode The string to URL encode
     */
    static String encodeURLString(String stringToEncode) {
        try {
            return URLEncoder.encode(stringToEncode, "UTF-8").replace("+", ENCODED_SPACE_CHARACTER);
        } catch (UnsupportedEncodingException e) {
            // should not happen - UTF-8 is a valid encoding
            throw new RuntimeException(e);
        }
    }
}
