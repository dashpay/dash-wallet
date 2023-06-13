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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import com.google.common.base.Charsets;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletExtension;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.dash.wallet.common.transactions.TransactionUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import javax.annotation.Nullable;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet_test.R;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;

import static org.dash.wallet.common.util.Constants.CHAR_THIN_SPACE;

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

    private static SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy KK:mm a",Locale.getDefault());
    private static SimpleDateFormat dateFormatNoYear = new SimpleDateFormat("MMM dd, KK:mm a", Locale.getDefault());

    public static String formatDate(long timeStamp) {
        Calendar calendar = Calendar.getInstance();
        Date currentDate = new Date(System.currentTimeMillis());
        calendar.setTime(currentDate);
        int currentYear = calendar.get(Calendar.YEAR);

        Date txDate = new Date(timeStamp);
        calendar.setTime(txDate);
        int txYear = calendar.get(Calendar.YEAR);
        SimpleDateFormat format = currentYear == txYear ? dateFormatNoYear : dateFormat;

        return format.format(timeStamp).replace("AM", "am").replace("PM", "pm");
    }

    public static Wallet restoreWalletFromProtobufOrBase58(final InputStream is,
                                                           final NetworkParameters expectedNetworkParameters) throws IOException {
        return restoreWalletFromProtobufOrBase58(is, expectedNetworkParameters, null);
    }

    public static Wallet restoreWalletFromProtobufOrBase58(final InputStream is,
            final NetworkParameters expectedNetworkParameters, @Nullable final WalletExtension[] walletExtensions) throws IOException {
        is.mark((int) Constants.BACKUP_MAX_CHARS);

        try {
            return restoreWalletFromProtobuf(is, expectedNetworkParameters, walletExtensions);
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
                                                   final NetworkParameters expectedNetworkParameters,
                                                   final WalletExtension[] walletExtensions) throws IOException {
        try {
            final Wallet wallet = new WalletProtobufSerializer().readWallet(is, true, walletExtensions);

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
                                                   final NetworkParameters expectedNetworkParameters,
                                               WalletExtension[] extensions) throws IOException {
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
            for (WalletExtension extension : extensions) {
                wallet.addExtension(extension);
            }

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

        out.write("# KEEP YOUR PRIVATE KEYS SAFE! Anyone who can read this can spend your Dash.\n");

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

    public static Boolean isKeysStream(InputStream is) {
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new InputStreamReader(is, Charsets.UTF_8));
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
            try {
                is.reset();
            } catch (IOException x) {
                //swallow
            }
        }
    }

    public static Boolean isUnencryptedStream(InputStream is) {
        try {
            return WalletProtobufSerializer.isWallet(is);
        } finally {
            try {
                is.reset();
            } catch (IOException x) {
                //swallow
            }
        }
    }

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


    public static @androidx.annotation.Nullable
    String uriToProvider(final Uri uri) {
        if (uri == null || !uri.getScheme().equals("content"))
            return null;
        final String host = uri.getHost();
        if ("com.google.android.apps.docs.storage".equals(host) || "com.google.android.apps.docs.storage.legacy".equals(host))
            return "Google Drive";
        if ("org.nextcloud.documents".equals(host))
            return "Nextcloud";
        if ("com.box.android.documents".equals(host))
            return "Box";
        if ("com.android.providers.downloads.documents".equals(host))
            return "internal storage";
        return null;
    }

    public static Date getTransactionDate(Transaction tx) {
        Date date = tx.getUpdateTime();
        if (tx.getConfidence() != null) {
            Date sentAtTime = tx.getConfidence().getSentAt();
            if (sentAtTime != null && sentAtTime.compareTo(date) < 0)
                date = sentAtTime;
        }
        return date;
    }

    // This creates the TaxBit CSV format
    public static String getTransactionHistory(Wallet wallet) {
        Set<Transaction> txSet = wallet.getTransactions(false);
        List<Transaction> txList = Arrays.asList(txSet.toArray(new Transaction[0]));

        Collections.sort(txList, (o1, o2) -> {
            Date tx1 = getTransactionDate(o1);
            Date tx2 = getTransactionDate(o2);
            return tx1.compareTo(tx2);
        });

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Date and Time,Transaction Type,Sent Quantity,Sent Currency,Sending Source,Received Quantity,Received Currency,Receiving Destination,Fee,Fee Currency,Exchange Transaction ID,Blockchain Transaction Hash").append("\n");
        @SuppressLint("SimpleDateFormat")
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(tz);
        for (Transaction tx : txList) {
            if (TransactionUtils.INSTANCE.isEntirelySelf(tx, wallet))
                continue;
            Coin value = tx.getValue(wallet);

            // Date and Time
            Date time = getTransactionDate(tx);
            stringBuilder.append(format.format(time)).append(",");
            // Transaction Type
            stringBuilder.append(value.isNegative() ? "Expense" : "Income").append(",");
            // Sent Quantity / Blank for incoming transactions
            if (value.isNegative()) {
                stringBuilder.append(MonetaryFormat.BTC.noCode().format(value.negate()));
            }
            stringBuilder.append(",");
            // Sent Currency / Blank for incoming transactions
            if (value.isNegative()) {
                stringBuilder.append(MonetaryFormat.BTC.code());
            }
            stringBuilder.append(",");
            // Sending Source / Blank for incoming transactions
            if (value.isNegative()) {
                stringBuilder.append("DASH Wallet");
            }
            stringBuilder.append(",");
            // Received Quantity / Blank for outgoing transactions
            if (value.isPositive()) {
                stringBuilder.append(MonetaryFormat.BTC.noCode().format(value));
            }
            stringBuilder.append(",");
            // Received Currency / Blank for outgoing transactions
            if (value.isPositive()) {
                stringBuilder.append(MonetaryFormat.BTC.code());
            }
            stringBuilder.append(",");
            // Receiving Destination / Blank for outgoing transactions
            if (value.isPositive()) {
                stringBuilder.append("DASH Wallet");
            }
            stringBuilder.append(",");

            // Fee: always blank
            stringBuilder.append(",");

            // Fee Currency: always blank
            stringBuilder.append(",");

            // Exchange Transaction ID: Always blank
            stringBuilder.append(",");

            // Blockchain Transaction Hash
            stringBuilder.append(tx.getTxId());

            stringBuilder.append("\n");
        }
        return stringBuilder.toString();
    }
}
