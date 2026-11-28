/*
 * Copyright 2014-2015 the original author or authors.
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

package org.dash.wallet.common.data;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.google.common.io.BaseEncoding;

import java.util.Arrays;
import java.util.Date;

import javax.annotation.Nullable;

import org.dash.wallet.common.money.Coin;
import org.dash.wallet.common.payments.bip70.PaymentProtocol;
import org.dash.wallet.common.payments.bip70.PaymentProtocolException;
import org.dash.wallet.common.payments.parsers.AddressFormatException;
import org.dash.wallet.common.payments.parsers.AddressNetwork;
import org.dash.wallet.common.payments.parsers.PaymentURI;
import org.dash.wallet.common.payments.parsers.Scripts;
import org.dash.wallet.common.util.Bluetooth;
import org.dash.wallet.common.util.GenericUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dash.wallet.common.util.Constants;

import static com.google.common.base.Preconditions.checkArgument;

import androidx.annotation.NonNull;

/**
 * Dashj-free payment intent: amounts are the self-contained {@link Coin} port, addresses are
 * base58 strings, output scripts are raw script bytes.
 *
 * @author Andreas Schildbach
 */
public final class PaymentIntent implements Parcelable {
    public enum Standard {
        BIP21, BIP70
    }

    public final static class Output implements Parcelable {
        public final Coin amount;
        public final byte[] scriptData;

        public Output(final Coin amount, final byte[] scriptData) {
            this.amount = amount;
            this.scriptData = scriptData;
        }

        public static Output valueOf(final PaymentProtocol.Output output)
                throws PaymentProtocolException.InvalidOutputs {
            // Reject structurally invalid scripts here, at BIP70 parse time, like the old
            // `new Script(scriptData)` did — not later, post-confirmation, in the send path.
            if (output.scriptData == null || output.scriptData.length == 0
                    || !Scripts.isParseable(output.scriptData)) {
                throw new PaymentProtocolException.InvalidOutputs(
                        "unparseable script in output: " + Constants.HEX.encode(output.scriptData == null ? new byte[0] : output.scriptData));
            }
            return new PaymentIntent.Output(output.amount, output.scriptData);
        }

        public boolean hasAmount() {
            return amount != null && amount.signum() != 0;
        }

        @NonNull
        @Override
        public String toString() {
            return toString(AddressNetwork.DASH_MAINNET);
        }

        public String toString(AddressNetwork network) {
            final StringBuilder builder = new StringBuilder();

            builder.append(getClass().getSimpleName());
            builder.append('[');
            builder.append(hasAmount() ? amount.toPlainString() : "null");
            builder.append(',');
            if (Scripts.isP2PKH(scriptData) || Scripts.isP2SH(scriptData))
                builder.append(Scripts.addressOf(scriptData, network));
            else if (Scripts.isP2PK(scriptData))
                builder.append(Constants.HEX.encode(Scripts.extractKeyFromP2PK(scriptData)));
            else if (Scripts.isMultisig(scriptData))
                builder.append("multisig");
            else if (Scripts.isOpReturn(scriptData))
                builder.append(Constants.HEX.encode(scriptData));
            else
                builder.append("unknown");
            builder.append(']');

            return builder.toString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(final Parcel dest, final int flags) {
            dest.writeSerializable(amount);

            dest.writeInt(scriptData.length);
            dest.writeByteArray(scriptData);
        }

        public static final Parcelable.Creator<Output> CREATOR = new Parcelable.Creator<Output>() {
            @Override
            public Output createFromParcel(final Parcel in) {
                return new Output(in);
            }

            @Override
            public Output[] newArray(final int size) {
                return new Output[size];
            }
        };

        private Output(final Parcel in) {
            amount = (Coin) in.readSerializable();

            final int programLength = in.readInt();
            scriptData = new byte[programLength];
            in.readByteArray(scriptData);
        }
    }

    @Nullable
    public final Standard standard;

    @Nullable
    public final String payeeName;

    @Nullable
    public final String payeeVerifiedBy;

    @Nullable
    public final Output[] outputs;

    @Nullable
    public final String memo;

    @Nullable
    public final String paymentUrl;

    @Nullable
    public final byte[] payeeData;

    @Nullable
    public final String paymentRequestUrl;

    @Nullable
    public final byte[] paymentRequestHash;

    public final Date expirationDate;

    @Nullable
    public final String payeeUserId;

    @Nullable
    public final String payeeUsername;

    public boolean shouldConfirmAddress = false;

    public String source = "";

    private static final Logger log = LoggerFactory.getLogger(PaymentIntent.class);

    public PaymentIntent(@Nullable final Standard standard, @Nullable final String payeeName,
                         @Nullable final String payeeVerifiedBy, @Nullable final Output[] outputs, @Nullable final String memo,
                         @Nullable final String paymentUrl, @Nullable final byte[] payeeData,
                         @Nullable final String paymentRequestUrl, @Nullable final byte[] paymentRequestHash,
                         @Nullable final String payeeUserId, @Nullable final String payeeUsername) {
        this(standard, payeeName, payeeVerifiedBy, outputs, memo, paymentUrl, payeeData, paymentRequestUrl, paymentRequestHash, null, payeeUserId, payeeUsername);
    }

    public PaymentIntent(@Nullable final Standard standard, @Nullable final String payeeName,
            @Nullable final String payeeVerifiedBy, @Nullable final Output[] outputs, @Nullable final String memo,
            @Nullable final String paymentUrl, @Nullable final byte[] payeeData,
            @Nullable final String paymentRequestUrl, @Nullable final byte[] paymentRequestHash,
            @Nullable final Date expirationDate, @Nullable final String payeeUserId, @Nullable final String payeeUsername) {
        this.standard = standard;
        this.payeeName = payeeName;
        this.payeeVerifiedBy = payeeVerifiedBy;
        this.outputs = outputs;
        this.memo = memo;
        this.paymentUrl = paymentUrl;
        this.payeeData = payeeData;
        this.paymentRequestUrl = paymentRequestUrl;
        this.paymentRequestHash = paymentRequestHash;
        this.expirationDate = expirationDate;
        this.payeeUserId = payeeUserId;
        this.payeeUsername = payeeUsername;
    }


    private PaymentIntent(final String address, @Nullable final String addressLabel) {
        this(null, null, null, buildSimplePayTo(Coin.ZERO, address), addressLabel, null, null, null,
                null, null, null);
    }


    public static PaymentIntent blank() {
        return new PaymentIntent(null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Creates a payment intent for the given base58 address (network inferred from the version byte). */
    public static PaymentIntent fromAddress(final String address, @Nullable final String addressLabel)
            throws AddressFormatException {
        return new PaymentIntent(address, addressLabel);
    }

    public static PaymentIntent fromAddressWithIdentity(final String address, @Nullable final String payeeUserId) {
        return new PaymentIntent(null, null, null, buildSimplePayTo(Coin.ZERO, address), null, null,
                null, null, null, payeeUserId, null);
    }

    public static PaymentIntent fromAddressWithIdentity(final String address, @Nullable final String payeeUserId, Coin amount) {
        return new PaymentIntent(null, null, null, buildSimplePayTo(amount, address), null, null,
                null, null, null, payeeUserId, null);
    }

    /** Creates a payment intent for the given base58 address, validated against the given network. */
    public static PaymentIntent fromAddress(final String address, @Nullable final String addressLabel, AddressNetwork network)
            throws AddressFormatException {
        Scripts.outputScriptForAddress(address, network); // validation
        return new PaymentIntent(address, addressLabel);
    }

    public static PaymentIntent fromUserId(final String payeeUserId) {
        return new PaymentIntent(null, null, null, null, null, null, null, null, null,
                payeeUserId, null);
    }

    public static PaymentIntent from(final String address, @Nullable final String addressLabel,
            @Nullable final Coin amount, AddressNetwork network) throws AddressFormatException {
        Scripts.outputScriptForAddress(address, network); // validation
        return new PaymentIntent(null, null, null,
                buildSimplePayTo(amount, address), addressLabel, null,
                null, null, null, null, null);
    }

    public static PaymentIntent fromPaymentUri(final PaymentURI paymentUri) {
        final String address = paymentUri.getAddress();
        final Output[] outputs = address != null ? buildSimplePayTo(paymentUri.getAmount(), address) : null;
        final String bluetoothMac = (String) paymentUri.getParameterByName(Bluetooth.MAC_URI_PARAM);
        final String paymentRequestHashStr = (String) paymentUri.getParameterByName("h");
        final byte[] paymentRequestHash = paymentRequestHashStr != null ? base64UrlDecode(paymentRequestHashStr) : null;
        final String dashPayUsername = paymentUri.getUser();

        return new PaymentIntent(PaymentIntent.Standard.BIP21, null, null, outputs, paymentUri.getLabel(),
                bluetoothMac != null ? "bt:" + bluetoothMac : null, null, paymentUri.getPaymentRequestUrl(),
                paymentRequestHash, null, dashPayUsername);
    }

    private static final BaseEncoding BASE64URL = BaseEncoding.base64Url().omitPadding();

    private static byte[] base64UrlDecode(final String encoded) {
        try {
            return BASE64URL.decode(encoded);
        } catch (final IllegalArgumentException x) {
            log.info("cannot base64url-decode: " + encoded);
            return null;
        }
    }

    public PaymentIntent mergeWithEditedValues(@Nullable final Coin editedAmount,
                                               @Nullable final String editedAddress) {
        final Output[] outputs;

        if (hasOutputs()) {
            if (mayEditAmount()) {
                checkArgument(editedAmount != null);

                // put all coins on first output, skip the others
                outputs = new Output[]{new Output(editedAmount, this.outputs[0].scriptData)};
            } else {
                // exact copy of outputs
                outputs = this.outputs;
            }
        } else {
            checkArgument(editedAmount != null);
            checkArgument(editedAddress != null);

            // custom output
            outputs = buildSimplePayTo(editedAmount, editedAddress);
        }

        return new PaymentIntent(standard, payeeName, payeeVerifiedBy, outputs, memo, null, payeeData, null, null, null, null);
    }

    private static Output[] buildSimplePayTo(final Coin amount, final String address) {
        return new Output[]{new Output(amount, Scripts.outputScriptForAddress(address))};
    }

    public boolean hasPayee() {
        return payeeName != null;
    }

    public boolean hasPayeeUserId() {
        return payeeUserId != null;
    }

    public boolean hasOutputs() {
        return outputs != null && outputs.length > 0;
    }

    public boolean hasAddress() {
        if (outputs == null || outputs.length != 1)
            return false;

        final byte[] script = outputs[0].scriptData;
        return Scripts.isP2PKH(script) || Scripts.isP2SH(script) || Scripts.isP2PK(script);
    }

    /** The destination address (base58) on the given network. */
    public String getAddress(AddressNetwork network) {
        if (!hasAddress())
            throw new IllegalStateException();

        final byte[] script = outputs[0].scriptData;
        return Scripts.addressOf(script, network, true);
    }

    public boolean mayEditAddress() {
        return standard == null;
    }

    public boolean hasAmount() {
        if (hasOutputs())
            for (final Output output : outputs)
                if (output.hasAmount())
                    return true;

        return false;
    }

    public Coin getAmount() {
        Coin amount = Coin.ZERO;

        if (hasOutputs())
            for (final Output output : outputs)
                if (output.hasAmount())
                    amount = amount.add(output.amount);

        if (amount.signum() != 0)
            return amount;
        else
            return null;
    }

    public boolean mayEditAmount() {
        return !(standard == Standard.BIP70 && hasAmount());
    }

    public boolean hasPaymentUrl() {
        return paymentUrl != null;
    }

    public boolean isSupportedPaymentUrl() {
        return isHttpPaymentUrl() || isBluetoothPaymentUrl();
    }

    public boolean isHttpPaymentUrl() {
        return paymentUrl != null && (GenericUtils.INSTANCE.startsWithIgnoreCase(paymentUrl, "http:")
                || GenericUtils.INSTANCE.startsWithIgnoreCase(paymentUrl, "https:"));
    }

    public boolean isBluetoothPaymentUrl() {
        return Bluetooth.isBluetoothUrl(paymentUrl);
    }

    public boolean hasPaymentRequestUrl() {
        return paymentRequestUrl != null;
    }

    public boolean isSupportedPaymentRequestUrl() {
        return isHttpPaymentRequestUrl() || isBluetoothPaymentRequestUrl();
    }

    public boolean isHttpPaymentRequestUrl() {
        return paymentRequestUrl != null && (GenericUtils.INSTANCE.startsWithIgnoreCase(paymentRequestUrl, "http:")
                || GenericUtils.INSTANCE.startsWithIgnoreCase(paymentRequestUrl, "https:"));
    }

    public boolean isBluetoothPaymentRequestUrl() {
        return Bluetooth.isBluetoothUrl(paymentRequestUrl);
    }

    public boolean isIdentityPaymentRequest() {
        return !TextUtils.isEmpty(payeeUserId) || !TextUtils.isEmpty(payeeUsername);
    }

    /**
     * Check if given payment intent is only extending on <i>this</i> one, that is it does not alter any of
     * the fields. Address and amount fields must be equal, respectively (non-existence included).
     * <p>
     * Alternatively, a BIP21+BIP72 request can provide a hash of the BIP70 request.
     *
     * @param other payment intent that is checked if it extends this one
     * @return true if it extends
     */
    public boolean isExtendedBy(final PaymentIntent other, boolean ignoreDetails, AddressNetwork network) {
        // shortcut via hash
        if (standard == Standard.BIP21 && other.standard == Standard.BIP70)
            if (paymentRequestHash != null && Arrays.equals(paymentRequestHash, other.paymentRequestHash))
                return true;

        return ignoreDetails || (equalsAmount(other) && equalsAddress(other, network));
    }

    public boolean equalsAmount(final PaymentIntent other) {
        final boolean hasAmount = hasAmount();
        if (hasAmount != other.hasAmount())
            return false;
        if (hasAmount && !getAmount().equals(other.getAmount()))
            return false;
        return true;
    }

    public boolean equalsAddress(final PaymentIntent other, AddressNetwork network) {
        final boolean hasAddress = hasAddress();
        if (hasAddress != other.hasAddress())
            return false;
        if (hasAddress && !getAddress(network).equals(other.getAddress(network)))
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();

        builder.append(getClass().getSimpleName());
        builder.append('[');
        builder.append(standard);
        builder.append(',');
        if (hasPayee()) {
            builder.append(payeeName);
            if (payeeVerifiedBy != null)
                builder.append("/").append(payeeVerifiedBy);
            builder.append(',');
        }
        builder.append(hasOutputs() ? Arrays.toString(outputs) : "null");
        builder.append(',');
        builder.append(paymentUrl);
        if (payeeData != null) {
            builder.append(",payeeData=");
            builder.append(Constants.HEX.encode(payeeData));
        }
        if (paymentRequestUrl != null) {
            builder.append(",paymentRequestUrl=");
            builder.append(paymentRequestUrl);
        }
        if (paymentRequestHash != null) {
            builder.append(",paymentRequestHash=");
            builder.append(Constants.HEX.encode(paymentRequestHash));
        }
        if (payeeUsername != null) {
            builder.append(",username=").append(payeeUsername);
        }
        if (hasPayeeUserId()) {
            builder.append(",userId=").append(payeeUserId);
        }
        builder.append(']');

        return builder.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        dest.writeSerializable(standard);

        dest.writeString(payeeName);
        dest.writeString(payeeVerifiedBy);

        if (outputs != null) {
            dest.writeInt(outputs.length);
            dest.writeTypedArray(outputs, 0);
        } else {
            dest.writeInt(0);
        }

        dest.writeString(memo);

        dest.writeString(paymentUrl);

        if (payeeData != null) {
            dest.writeInt(payeeData.length);
            dest.writeByteArray(payeeData);
        } else {
            dest.writeInt(0);
        }

        dest.writeString(paymentRequestUrl);

        if (paymentRequestHash != null) {
            dest.writeInt(paymentRequestHash.length);
            dest.writeByteArray(paymentRequestHash);
        } else {
            dest.writeInt(0);
        }
        if (expirationDate != null) {
            dest.writeLong(expirationDate.getTime());
        } else {
            dest.writeLong(0);
        }
        dest.writeString(payeeUserId);
        dest.writeString(payeeUsername);
        dest.writeInt(shouldConfirmAddress ? 1 : 0);
        dest.writeString(source);
    }

    public static final Parcelable.Creator<PaymentIntent> CREATOR = new Parcelable.Creator<PaymentIntent>() {
        @Override
        public PaymentIntent createFromParcel(final Parcel in) {
            return new PaymentIntent(in);
        }

        @Override
        public PaymentIntent[] newArray(final int size) {
            return new PaymentIntent[size];
        }
    };

    private PaymentIntent(final Parcel in) {
        standard = (Standard) in.readSerializable();

        payeeName = in.readString();
        payeeVerifiedBy = in.readString();

        final int outputsLength = in.readInt();
        if (outputsLength > 0) {
            outputs = new Output[outputsLength];
            in.readTypedArray(outputs, Output.CREATOR);
        } else {
            outputs = null;
        }

        memo = in.readString();

        paymentUrl = in.readString();

        final int payeeDataLength = in.readInt();
        if (payeeDataLength > 0) {
            payeeData = new byte[payeeDataLength];
            in.readByteArray(payeeData);
        } else {
            payeeData = null;
        }

        paymentRequestUrl = in.readString();

        final int paymentRequestHashLength = in.readInt();
        if (paymentRequestHashLength > 0) {
            paymentRequestHash = new byte[paymentRequestHashLength];
            in.readByteArray(paymentRequestHash);
        } else {
            paymentRequestHash = null;
        }
        final long expirationDateLong = in.readLong();
        expirationDate = (expirationDateLong > 0) ? new Date(expirationDateLong) : null;

        payeeUserId = in.readString();
        payeeUsername = in.readString();
        shouldConfirmAddress = in.readInt() == 1;
        source = in.readString();
    }

    public boolean getExpired() {
        if (expirationDate != null) {
            return new Date().after(expirationDate);
        } else {
            return false;
        }
    }
}
