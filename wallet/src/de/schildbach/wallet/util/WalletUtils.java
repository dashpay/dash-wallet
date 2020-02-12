/*
 * Copyright 2011-2015 the original author or authors.
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

package de.schildbach.wallet.util;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nullable;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.CoinDefinition;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer;

import com.google.common.base.Charsets;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet_test.R;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;

import static org.dash.wallet.common.Constants.CHAR_THIN_SPACE;

/**
 * @author Andreas Schildbach
 */
public class WalletUtils {
    public static Editable formatAddress(final Address address, final int groupSize, final int lineSize) {
        return formatHash(address.toString(), groupSize, lineSize);
    }

    public static Editable formatAddress(@Nullable final String prefix, final Address address, final int groupSize,
            final int lineSize) {
        return formatHash(prefix, address.toString(), groupSize, lineSize, CHAR_THIN_SPACE);
    }

    public static Editable formatHash(final String address, final int groupSize, final int lineSize) {
        return formatHash(null, address, groupSize, lineSize, CHAR_THIN_SPACE);
    }

    public static long longHash(final Sha256Hash hash) {
        final byte[] bytes = hash.getBytes();

        return (bytes[31] & 0xFFl) | ((bytes[30] & 0xFFl) << 8) | ((bytes[29] & 0xFFl) << 16)
                | ((bytes[28] & 0xFFl) << 24) | ((bytes[27] & 0xFFl) << 32) | ((bytes[26] & 0xFFl) << 40)
                | ((bytes[25] & 0xFFl) << 48) | ((bytes[23] & 0xFFl) << 56);
    }

    public static Editable formatHash(@Nullable final String prefix, final String address, final int groupSize,
            final int lineSize, final char groupSeparator) {
        final SpannableStringBuilder builder = prefix != null ? new SpannableStringBuilder(prefix)
                : new SpannableStringBuilder();

        final int len = address.length();
        for (int i = 0; i < len; i += groupSize) {
            final int end = i + groupSize;
            final String part = address.substring(i, end < len ? end : len);

            builder.append(part);
            if (end < len) {
                final boolean endOfLine = lineSize > 0 && end % lineSize == 0;
                builder.append(endOfLine ? '\n' : groupSeparator);
            }
        }

        return builder;
    }

    @Nullable
    public static Address getWalletAddressOfReceived(final Transaction tx, final Wallet wallet) {
        for (final TransactionOutput output : tx.getOutputs()) {
            try {
                if (output.isMine(wallet)) {
                    final Script script = output.getScriptPubKey();
                    return script.getToAddress(Constants.NETWORK_PARAMETERS, true);
                }
            } catch (final ScriptException x) {
                // swallow
            }
        }

        return null;
    }

    public static List<Address> getFromAddressOfSent(final Transaction tx, final Wallet wallet) {
        List<Address> result = new ArrayList<>();

        for (final TransactionInput input : tx.getInputs()) {
            try {
                Transaction connectedTransaction = input.getConnectedTransaction();
                if (connectedTransaction != null) {
                    TransactionOutput output = connectedTransaction.getOutput(input.getOutpoint().getIndex());
                    final Script script = output.getScriptPubKey();
                    result.add(script.getToAddress(Constants.NETWORK_PARAMETERS, true));
                }
            } catch (final ScriptException x) {
                // swallow
            }
        }

        return result;
    }

    public static List<Address> getToAddressOfReceived(final Transaction tx, final  Wallet wallet) {
        List<Address> result = new ArrayList<>();

        for (TransactionOutput output : tx.getOutputs()) {
            try {
                if (output.isMine(wallet)) {
                    final Script script = output.getScriptPubKey();
                    result.add(script.getToAddress(Constants.NETWORK_PARAMETERS, true));
                }
            } catch (final ScriptException x) {
                // swallow
            }
        }

        return result;
    }

    public static List<Address> getToAddressOfSent(final Transaction tx, final Wallet wallet) {
        List<Address> result = new ArrayList<>();

        for (TransactionOutput output : tx.getOutputs()) {
            try {
                if (!output.isMine(wallet)) {
                    final Script script = output.getScriptPubKey();
                    result.add(script.getToAddress(Constants.NETWORK_PARAMETERS, true));
                }
            } catch (final ScriptException x) {
                // swallow
            }
        }

        return result;
    }


    public static boolean isEntirelySelf(final Transaction tx, final Wallet wallet) {
        for (final TransactionInput input : tx.getInputs()) {
            final TransactionOutput connectedOutput = input.getConnectedOutput();
            if (connectedOutput == null || !connectedOutput.isMine(wallet))
                return false;
        }

        for (final TransactionOutput output : tx.getOutputs()) {
            if (!output.isMine(wallet))
                return false;
        }

        return true;
    }

    public static Wallet restoreWalletFromProtobufOrBase58(final InputStream is,
            final NetworkParameters expectedNetworkParameters) throws IOException {
        is.mark((int) Constants.BACKUP_MAX_CHARS);

        try {
            return restoreWalletFromProtobuf(is, expectedNetworkParameters);
        } catch (final IOException x) {
            try {
                is.reset();
                Wallet wallet = restorePrivateKeysFromBase58(is, expectedNetworkParameters);
                wallet.upgradeToDeterministic(null); //this will result in a different HD seed each time
                return wallet;
            } catch (final IOException x2) {
                throw new IOException(
                        "cannot read protobuf (" + x.getMessage() + ") or base58 (" + x2.getMessage() + ")", x);
            }
        }
    }

    public static Wallet restoreWalletFromProtobuf(final InputStream is,
            final NetworkParameters expectedNetworkParameters) throws IOException {
        try {
            final Wallet wallet = new WalletProtobufSerializer().readWallet(is, true, null);

            if (!wallet.getParams().equals(expectedNetworkParameters))
                throw new IOException("bad wallet backup network parameters: " + wallet.getParams().getId());
            if (!wallet.isConsistent())
                throw new IOException("inconsistent wallet backup");

            return wallet;
        } catch (final UnreadableWalletException x) {
            throw new IOException("unreadable wallet", x);
        }
    }

    public static Wallet restoreWalletFromSeed(final List<String> words,
                                                   final NetworkParameters expectedNetworkParameters) throws IOException {
        try {
            DeterministicSeed seed =  new DeterministicSeed(words, null,"", Constants.EARLIEST_HD_SEED_CREATION_TIME);
            KeyChainGroup group = KeyChainGroup.builder(Constants.NETWORK_PARAMETERS)
                    .fromSeed(seed, Script.ScriptType.P2PKH)
                    .addChain(DeterministicKeyChain.builder()
                            .seed(seed)
                            .accountPath(Constants.BIP44_PATH)
                            .build())
                    .build();

            final Wallet wallet = new Wallet(Constants.NETWORK_PARAMETERS, group);

            if (!wallet.getParams().equals(expectedNetworkParameters))
                throw new IOException("bad wallet backup network parameters: " + wallet.getParams().getId());
            if (!wallet.isConsistent())
                throw new IOException("inconsistent wallet backup");

            return wallet;
        } finally {

        }

    }

    public static Wallet restorePrivateKeysFromBase58(final InputStream is,
            final NetworkParameters expectedNetworkParameters) throws IOException {
        final BufferedReader keyReader = new BufferedReader(new InputStreamReader(is, Charsets.UTF_8));

        // create non-HD wallet
        final KeyChainGroup group = KeyChainGroup.builder(expectedNetworkParameters).build();

        group.importKeys(WalletUtils.readKeys(keyReader, expectedNetworkParameters));
        return new Wallet(expectedNetworkParameters, group);
    }

    public static void writeKeys(final Writer out, final List<ECKey> keys) throws IOException {
        final DateFormat format = Iso8601Format.newDateTimeFormatT();

        out.write("# KEEP YOUR PRIVATE KEYS SAFE! Anyone who can read this can spend your "+ CoinDefinition.coinName+"s.\n");

        for (final ECKey key : keys) {
            out.write(key.getPrivateKeyEncoded(Constants.NETWORK_PARAMETERS).toString());
            if (key.getCreationTimeSeconds() != 0) {
                out.write(' ');
                out.write(format.format(new Date(key.getCreationTimeSeconds() * DateUtils.SECOND_IN_MILLIS)));
            }
            out.write('\n');
        }
    }

    public static List<ECKey> readKeys(final BufferedReader in, final NetworkParameters expectedNetworkParameters)
            throws IOException {
        try {
            final DateFormat format = Iso8601Format.newDateTimeFormatT();

            final List<ECKey> keys = new LinkedList<ECKey>();

            long charCount = 0;
            while (true) {
                final String line = in.readLine();
                if (line == null)
                    break; // eof
                charCount += line.length();
                if (charCount > Constants.BACKUP_MAX_CHARS)
                    throw new IOException("read more than the limit of " + Constants.BACKUP_MAX_CHARS + " characters");
                if (line.trim().isEmpty() || line.charAt(0) == '#')
                    continue; // skip comment

                final String[] parts = line.split(" ");

                final ECKey key = DumpedPrivateKey.fromBase58(expectedNetworkParameters, parts[0]).getKey();
                key.setCreationTimeSeconds(
                        parts.length >= 2 ? format.parse(parts[1]).getTime() / DateUtils.SECOND_IN_MILLIS : 0);

                keys.add(key);
            }

            return keys;
        } catch (final AddressFormatException x) {
            throw new IOException("cannot read keys", x);
        } catch (final ParseException x) {
            throw new IOException("cannot read keys", x);
        }
    }

    public static final FileFilter KEYS_FILE_FILTER = new FileFilter() {
        @Override
        public boolean accept(final File file) {
            BufferedReader reader = null;

            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), Charsets.UTF_8));
                WalletUtils.readKeys(reader, Constants.NETWORK_PARAMETERS);

                return true;
            } catch (final IOException x) {
                return false;
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (final IOException x) {
                        // swallow
                    }
                }
            }
        }
    };

    public static final FileFilter BACKUP_FILE_FILTER = new FileFilter() {
        @Override
        public boolean accept(final File file) {
            InputStream is = null;

            try {
                is = new FileInputStream(file);
                return WalletProtobufSerializer.isWallet(is);
            } catch (final IOException x) {
                return false;
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (final IOException x) {
                        // swallow
                    }
                }
            }
        }
    };

    public static byte[] walletToByteArray(final Wallet wallet) {
        try {
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            new WalletProtobufSerializer().writeWallet(wallet, os);
            os.close();
            return os.toByteArray();
        } catch (final IOException x) {
            throw new RuntimeException(x);
        }
    }

    public static Wallet walletFromByteArray(final byte[] walletBytes) {
        try {
            final ByteArrayInputStream is = new ByteArrayInputStream(walletBytes);
            final Wallet wallet = new WalletProtobufSerializer().readWallet(is);
            is.close();
            return wallet;
        } catch (final UnreadableWalletException x) {
            throw new RuntimeException(x);
        } catch (final IOException x) {
            throw new RuntimeException(x);
        }
    }

    public static boolean isPayToManyTransaction(final Transaction transaction) {
        return transaction.getOutputs().size() > 20;
    }

    public static String buildShortAddress(String longAddress) {
        StringBuilder addressBuilder = new StringBuilder(longAddress.substring(0,
                Constants.ADDRESS_FORMAT_FIRST_SECTION_SIZE));
        addressBuilder.append(Constants.ADDRESS_FORMAT_SECTION_SEPARATOR);
        int lastSectionStart = longAddress.length() - Constants.ADDRESS_FORMAT_LAST_SECTION_SIZE;
        addressBuilder.append(longAddress.substring(lastSectionStart));
        return addressBuilder.toString();
    }

    public static void viewOnBlockExplorer(Context context, Transaction.Purpose txPurpose,
                                           String txHash) {
        Uri blockExplorer = WalletApplication.getInstance()
                .getConfiguration()
                .getBlockExplorer(R.array.preferences_block_explorer_values);
        Uri keyRotationUri = Uri.parse("https://bitcoin.org/en/alert/2013-08-11-android");
        boolean txRotation = txPurpose == Transaction.Purpose.KEY_ROTATION;
        if (!txRotation) {
            context.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.withAppendedPath(blockExplorer, "tx/" + txHash)));
        } else {
            context.startActivity(new Intent(Intent.ACTION_VIEW, keyRotationUri));
        }
    }

}
