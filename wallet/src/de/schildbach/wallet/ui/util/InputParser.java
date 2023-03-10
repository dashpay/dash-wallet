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

package de.schildbach.wallet.ui.util;

import android.net.Uri;

import com.google.common.hash.Hashing;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UninitializedMessageException;

import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PrefixedChecksummedBytes;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.bitcoinj.crypto.TrustStoreLoader;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protocols.payments.PaymentProtocol.PkiVerificationData;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import org.bitcoinj.protocols.payments.PaymentSession;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.uri.BitcoinURIParseException;
import org.dash.wallet.common.util.Qr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.ui.send.SendCoinsActivity;
import de.schildbach.wallet.util.AddressUtil;
import de.schildbach.wallet.util.Io;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public abstract class InputParser {
    private static final Logger log = LoggerFactory.getLogger(InputParser.class);

    public abstract static class StringInputParser extends InputParser {
        private final String input;

        public StringInputParser(final String input, boolean supportAnypayUrls) {
            if (supportAnypayUrls) {
                // replaces Anypay scheme with the Dash one
                // ie "pay:?r=https://(...)" become "dash:?r=https://(...)"
                if (input.startsWith(SendCoinsActivity.ANYPAY_SCHEME + ":")) {
                    this.input = input.replaceFirst(SendCoinsActivity.ANYPAY_SCHEME, SendCoinsActivity.DASH_SCHEME);
                    return;
                }
            }
            this.input = input;
        }

        @Override
        public void parse() {
            if (input.startsWith(SendCoinsActivity.DASH_SCHEME.toUpperCase() + ":-")) {
                try {
                    final byte[] serializedPaymentRequest = Qr.INSTANCE.decodeBinary(input.substring(9));

                    parseAndHandlePaymentRequest(serializedPaymentRequest);
                } catch (final IOException x) {
                    log.info("i/o error while fetching payment request", x);

                    error(x, R.string.input_parser_io_error, x.getMessage());
                } catch (final PaymentProtocolException.PkiVerificationException x) {
                    log.info("got unverifyable payment request", x);

                    error(x, R.string.input_parser_unverifyable_paymentrequest, x.getMessage());
                } catch (final PaymentProtocolException x) {
                    log.info("got invalid payment request", x);

                    error(x, R.string.input_parser_invalid_paymentrequest, x.getMessage());
                }
            } else if (input.startsWith(SendCoinsActivity.DASH_SCHEME + ":")) {
                try {
                    final BitcoinURI bitcoinUri = new BitcoinURI(null, input);
                    final Address address = AddressUtil.getCorrectAddress(bitcoinUri);
                    if (address != null && !Constants.NETWORK_PARAMETERS.equals(address.getParameters()))
                        throw new BitcoinURIParseException("mismatched network");

                    handlePaymentIntent(PaymentIntent.fromBitcoinUri(bitcoinUri));
                } catch (final BitcoinURIParseException x) {
                    if (!tryFindAnyMatch(input)) {
                        log.info("got invalid bitcoin uri: '" + input + "'", x);
                        error(x, R.string.input_parser_invalid_bitcoin_uri, input);
                    }
                }
            } else if (PATTERN_BITCOIN_ADDRESS.matcher(input).matches()) {
                try {
                    final Address address = Address.fromString(Constants.NETWORK_PARAMETERS, input);

                    handlePaymentIntent(PaymentIntent.fromAddress(address, null));
                } catch (final AddressFormatException x) {
                    log.info("got invalid address", x);

                    error(x, R.string.input_parser_invalid_address);
                }
            } else if (PATTERN_DUMPED_PRIVATE_KEY_UNCOMPRESSED.matcher(input).matches()
                    || PATTERN_DUMPED_PRIVATE_KEY_COMPRESSED.matcher(input).matches()) {
                try {
                    final PrefixedChecksummedBytes key = DumpedPrivateKey.fromBase58(Constants.NETWORK_PARAMETERS,
                            input);

                    handlePrivateKey(key);
                } catch (final AddressFormatException x) {
                    log.info("got invalid address", x);

                    error(x, R.string.input_parser_invalid_address);
                }
            } else if (PATTERN_BIP38_PRIVATE_KEY.matcher(input).matches()) {
                try {
                    final PrefixedChecksummedBytes key = BIP38PrivateKey.fromBase58(Constants.NETWORK_PARAMETERS,
                            input);

                    handlePrivateKey(key);
                } catch (final AddressFormatException x) {
                    log.info("got invalid address", x);

                    error(x, R.string.input_parser_invalid_address);
                }
            } else if (PATTERN_TRANSACTION.matcher(input).matches()) {
                try {
                    final Transaction tx = new Transaction(Constants.NETWORK_PARAMETERS,
                            Qr.INSTANCE.decodeDecompressBinary(input));

                    handleDirectTransaction(tx);
                } catch (final IOException x) {
                    log.info("i/o error while fetching transaction", x);

                    error(x, R.string.input_parser_invalid_transaction, x.getMessage());
                } catch (final ProtocolException x) {
                    log.info("got invalid transaction", x);

                    error(x, R.string.input_parser_invalid_transaction, x.getMessage());
                }
            } else if (!tryFindAnyMatch(input)) {
                cannotClassify(input);
            }
        }

        private boolean tryFindAnyMatch(String input) {
            Matcher matcher = PATTERN_BITCOIN_ADDRESS.matcher(input);

            if (matcher.find() && matcher.group(0) != null) {
                try {
                    String addressStr = matcher.group(0);
                    assert addressStr != null;
                    final Address address = Address.fromString(Constants.NETWORK_PARAMETERS, addressStr);
                    PaymentIntent intent = PaymentIntent.fromAddress(address, null);
                    intent.setShouldConfirmAddress(true);

                    handlePaymentIntent(intent);
                } catch (final AddressFormatException x) {
                    log.info("got invalid address", x);
                    error(x, R.string.input_parser_invalid_address);
                }

                return true;
            }

            return false;
        }

        protected void handlePrivateKey(final PrefixedChecksummedBytes key) {
            final Address address = Address.fromKey(Constants.NETWORK_PARAMETERS,
                    ((DumpedPrivateKey) key).getKey());

            handlePaymentIntent(PaymentIntent.fromAddress(address, null));
        }
    }

    public abstract static class BinaryInputParser extends InputParser {
        private final String inputType;
        private final byte[] input;

        public BinaryInputParser(final String inputType, final byte[] input) {
            this.inputType = inputType;
            this.input = input;
        }

        @Override
        public void parse() {
            if (Constants.MIMETYPE_TRANSACTION.equals(inputType)) {
                try {
                    final Transaction tx = new Transaction(Constants.NETWORK_PARAMETERS, input);

                    handleDirectTransaction(tx);
                } catch (final VerificationException x) {
                    log.info("got invalid transaction", x);

                    error(x, R.string.input_parser_invalid_transaction, x.getMessage());
                }
            } else if (PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(inputType)) {
                try {
                    parseAndHandlePaymentRequest(input);
                } catch (final PaymentProtocolException.PkiVerificationException x) {
                    log.info("got unverifyable payment request", x);

                    error(x, R.string.input_parser_unverifyable_paymentrequest, x.getMessage());
                } catch (final PaymentProtocolException x) {
                    log.info("got invalid payment request", x);

                    error(x, R.string.input_parser_invalid_paymentrequest, x.getMessage());
                }
            } else {
                cannotClassify(inputType);
            }
        }

        @Override
        protected final void handleDirectTransaction(final Transaction transaction) throws VerificationException {
            throw new UnsupportedOperationException();
        }
    }

    public abstract static class StreamInputParser extends InputParser {
        private final String inputType;
        private final InputStream is;

        public StreamInputParser(final String inputType, final InputStream is) {
            this.inputType = inputType;
            this.is = is;
        }

        @Override
        public void parse() {
            if (PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(inputType)) {
                ByteArrayOutputStream baos = null;

                try {
                    baos = new ByteArrayOutputStream();
                    Io.copy(is, baos);
                    parseAndHandlePaymentRequest(baos.toByteArray());
                } catch (final IOException x) {
                    log.info("i/o error while fetching payment request", x);

                    error(x, R.string.input_parser_io_error, x.getMessage());
                } catch (final PaymentProtocolException.PkiVerificationException x) {
                    log.info("got unverifyable payment request", x);

                    error(x, R.string.input_parser_unverifyable_paymentrequest, x.getMessage());
                } catch (final PaymentProtocolException x) {
                    log.info("got invalid payment request", x);

                    error(x, R.string.input_parser_invalid_paymentrequest, x.getMessage());
                } finally {
                    try {
                        if (baos != null)
                            baos.close();
                    } catch (IOException x) {
                        x.printStackTrace();
                    }

                    try {
                        is.close();
                    } catch (IOException x) {
                        x.printStackTrace();
                    }
                }
            } else {
                cannotClassify(inputType);
            }
        }

        @Override
        protected final void handleDirectTransaction(final Transaction transaction) throws VerificationException {
            throw new UnsupportedOperationException();
        }
    }

    public abstract static class WalletUriParser extends InputParser {
        private final Uri input;

        public WalletUriParser(final Uri input) {
            this.input = input;
        }

        @Override
        public void parse() {
            WalletUri walletUri;
            try {
                walletUri = WalletUri.parse(input);
            } catch (BitcoinURIParseException x) {
                log.info("got invalid dashwallet uri: '" + input + "'", x);

                error(x, R.string.input_parser_invalid_bitcoin_uri, input);
                return;
            }

            if (walletUri.isPaymentUri()) {
                BitcoinURI bitcoinUri;
                try {
                    bitcoinUri = walletUri.toBitcoinUri();
                    handlePaymentIntent(PaymentIntent.fromBitcoinUri(bitcoinUri), walletUri.forceInstantSend());
                } catch (BitcoinURIParseException x) {
                    log.info("got invalid dashwallet uri: '" + input + "'", x);

                    error(x, R.string.input_parser_invalid_bitcoin_uri, input);
                }
            } else if (walletUri.isMasterPublicKeyRequest()) {
                handleMasterPublicKeyRequest(walletUri.getSender());
            } else if (walletUri.isAddressRequest()) {
                handleAddressRequest(walletUri.getSender());
            } else {
                cannotClassify(input.toString());
            }
        }

        protected abstract void handleMasterPublicKeyRequest(String sender);
        protected abstract void handleAddressRequest(String sender);

        @Override
        protected void handleDirectTransaction(final Transaction transaction) throws VerificationException {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void handlePaymentIntent(PaymentIntent paymentIntent) {
            handlePaymentIntent(paymentIntent, false);
        }

        protected abstract void handlePaymentIntent(PaymentIntent paymentIntent, boolean forceInstantSend);
    }

    public abstract void parse();

    protected final void parseAndHandlePaymentRequest(final byte[] serializedPaymentRequest)
            throws PaymentProtocolException {
        final PaymentIntent paymentIntent = parsePaymentRequest(serializedPaymentRequest);

        handlePaymentIntent(paymentIntent);
    }

    public static PaymentIntent parsePaymentRequest(final byte[] serializedPaymentRequest)
            throws PaymentProtocolException {
        try {
            if (serializedPaymentRequest.length > 50000)
                throw new PaymentProtocolException("payment request too big: " + serializedPaymentRequest.length);

            final Protos.PaymentRequest paymentRequest = Protos.PaymentRequest.parseFrom(serializedPaymentRequest);

            final String pkiName;
            final String pkiCaName;
            if (!"none".equals(paymentRequest.getPkiType())) {
                final KeyStore keystore = new TrustStoreLoader.DefaultTrustStoreLoader().getKeyStore();
                final PkiVerificationData verificationData = PaymentProtocol.verifyPaymentRequestPki(paymentRequest,
                        keystore);
                pkiName = verificationData.displayName;
                pkiCaName = verificationData.rootAuthorityName;
            } else {
                pkiName = null;
                pkiCaName = null;
            }

            final PaymentSession paymentSession = PaymentProtocol.parsePaymentRequest(paymentRequest);

            if (paymentSession.isExpired())
                throw new PaymentProtocolException.Expired("payment details expired: current time " + new Date()
                        + " after expiry time " + paymentSession.getExpires());

            if (!paymentSession.getNetworkParameters().equals(Constants.NETWORK_PARAMETERS))
                throw new PaymentProtocolException.InvalidNetwork(
                        "cannot handle payment request network: " + paymentSession.getNetworkParameters());

            final ArrayList<PaymentIntent.Output> outputs = new ArrayList<PaymentIntent.Output>(1);
            for (final PaymentProtocol.Output output : paymentSession.getOutputs())
                outputs.add(PaymentIntent.Output.valueOf(output));

            final String memo = paymentSession.getMemo();

            final String paymentUrl = paymentSession.getPaymentUrl();

            final byte[] merchantData = paymentSession.getMerchantData();

            final byte[] paymentRequestHash = Hashing.sha256().hashBytes(serializedPaymentRequest).asBytes();

            final PaymentIntent paymentIntent = new PaymentIntent(PaymentIntent.Standard.BIP70, pkiName, pkiCaName,
                    outputs.toArray(new PaymentIntent.Output[0]), memo, paymentUrl, merchantData, null,
                    paymentRequestHash, paymentSession.getExpires());

            if (paymentIntent.hasPaymentUrl() && !paymentIntent.isSupportedPaymentUrl())
                throw new PaymentProtocolException.InvalidPaymentURL(
                        "cannot handle payment url: " + paymentIntent.paymentUrl);

            return paymentIntent;
        } catch (final InvalidProtocolBufferException x) {
            throw new PaymentProtocolException(x);
        } catch (final UninitializedMessageException x) {
            throw new PaymentProtocolException(x);
        } catch (final FileNotFoundException x) {
            throw new RuntimeException(x);
        } catch (final KeyStoreException x) {
            throw new RuntimeException(x);
        }
    }

    protected abstract void handlePaymentIntent(PaymentIntent paymentIntent);

    protected abstract void handleDirectTransaction(Transaction transaction) throws VerificationException;

    protected abstract void error(Exception x, int messageResId, Object... messageArgs);

    protected void cannotClassify(final String input) {
        log.info("cannot classify: '{}'", input);

        error(null, R.string.input_parser_cannot_classify, input);
    }

    private static final Pattern PATTERN_BITCOIN_ADDRESS = Pattern
            .compile("[" + new String(Base58.ALPHABET) + "]{20,40}");
    private static final Pattern PATTERN_DUMPED_PRIVATE_KEY_UNCOMPRESSED = Pattern
            .compile((Constants.NETWORK_PARAMETERS.getId().equals(NetworkParameters.ID_MAINNET) ? "7" : "9") + "["
                    + new String(Base58.ALPHABET) + "]{50}");
    private static final Pattern PATTERN_DUMPED_PRIVATE_KEY_COMPRESSED = Pattern
            .compile((Constants.NETWORK_PARAMETERS.getId().equals(NetworkParameters.ID_MAINNET) ? "X" : "c") + "["
                    + new String(Base58.ALPHABET) + "]{51}");
    private static final Pattern PATTERN_BIP38_PRIVATE_KEY = Pattern
            .compile("6P" + "[" + new String(Base58.ALPHABET) + "]{56}");
    private static final Pattern PATTERN_TRANSACTION = Pattern
            .compile("[0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$\\*\\+\\-\\.\\/\\:]{100,}");
}
