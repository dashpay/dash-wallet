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

package de.schildbach.wallet;

import android.os.Build;
import android.os.Environment;
import android.text.format.DateUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;

import org.bitcoinj.core.Context;
import org.bitcoinj.core.MasternodeSync;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.ScrewDriverDevNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import de.schildbach.wallet_test.BuildConfig;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * @author Andreas Schildbach
 */
public final class Constants {

    /** Network this wallet is on (e.g. testnet or mainnet). */
    public static final NetworkParameters NETWORK_PARAMETERS;

    private static String FILENAME_NETWORK_SUFFIX;

    /** Currency code for the wallet name resolver. */
    public static String WALLET_NAME_CURRENCY_CODE;

    public static final String[] DNS_SEED;

    public static final boolean IS_PROD_BUILD;

    public static final EnumSet<MasternodeSync.SYNC_FLAGS> SYNC_FLAGS = MasternodeSync.SYNC_DEFAULT_SPV;
    public static final EnumSet<MasternodeSync.VERIFY_FLAGS> VERIFY_FLAGS = MasternodeSync.VERIFY_DEFAULT_SPV;

    static {
        switch (BuildConfig.FLAVOR) {
            case "prod": {
                DNS_SEED = new String[]{"dnsseed.dash.org", "dnsseed.dashdot.io"};
                BIP44_PATH = DeterministicKeyChain.BIP44_ACCOUNT_ZERO_PATH;
                NETWORK_PARAMETERS = MainNetParams.get();
                IS_PROD_BUILD = true;
                FILENAME_NETWORK_SUFFIX = "";
                WALLET_NAME_CURRENCY_CODE = "dash";
                org.dash.wallet.common.util.Constants.INSTANCE.setEXPLORE_GC_FILE_PATH("explore/explore.db");
                SYNC_FLAGS.add(MasternodeSync.SYNC_FLAGS.SYNC_HEADERS_MN_LIST_FIRST);
                break;
            }
            case "_testNet3": {
                DNS_SEED = new String[]{"testnet-seed.dashdot.io", "95.183.51.146", "35.161.101.35", "54.91.130.170"};
                BIP44_PATH = DeterministicKeyChain.BIP44_ACCOUNT_ZERO_PATH_TESTNET;
                NETWORK_PARAMETERS = TestNet3Params.get();
                IS_PROD_BUILD = false;
                FILENAME_NETWORK_SUFFIX = "-testnet";
                WALLET_NAME_CURRENCY_CODE = "tdash";
                org.dash.wallet.common.util.Constants.INSTANCE.setEXPLORE_GC_FILE_PATH("explore/explore-testnet.db");
                SYNC_FLAGS.add(MasternodeSync.SYNC_FLAGS.SYNC_HEADERS_MN_LIST_FIRST);
                break;
            }
            case "devnet": {
                // Devnet
                BIP44_PATH = DeterministicKeyChain.BIP44_ACCOUNT_ZERO_PATH_TESTNET;
                NETWORK_PARAMETERS = ScrewDriverDevNetParams.get();
                // TODO: remove this next line when Platform Supports Core 0.18
                DNS_SEED = NETWORK_PARAMETERS.getDnsSeeds();
                IS_PROD_BUILD = false;
                FILENAME_NETWORK_SUFFIX = "-chacha";
                WALLET_NAME_CURRENCY_CODE = "tdash";
                org.dash.wallet.common.util.Constants.EXPLORE_GC_FILE_PATH = "explore/explore-devnet.db";
                SYNC_FLAGS.add(MasternodeSync.SYNC_FLAGS.SYNC_HEADERS_MN_LIST_FIRST);
                break;
            }
            default: {
                throw new IllegalStateException("Unsupported flavor " + BuildConfig.FLAVOR);
            }
        }
        org.dash.wallet.common.util.Constants.INSTANCE.setMAX_MONEY(NETWORK_PARAMETERS.getMaxMoney());
    }

    /** Bitcoinj global context. */
    public static final Context CONTEXT = new Context(NETWORK_PARAMETERS);

    public final static class Files {

        /** Filename of the wallet. */
        public static final String WALLET_FILENAME_PROTOBUF = "wallet-protobuf" + FILENAME_NETWORK_SUFFIX;

        /** How often the wallet is autosaved. */
        public static final long WALLET_AUTOSAVE_DELAY_MS = 5 * DateUtils.SECOND_IN_MILLIS;

        /** Filename of the automatic key backup (old format, can only be read). */
        public static final String WALLET_KEY_BACKUP_BASE58 = "key-backup-base58" + FILENAME_NETWORK_SUFFIX;

        /** Filename of the automatic wallet backup. */
        public static final String WALLET_KEY_BACKUP_PROTOBUF = "key-backup-protobuf" + FILENAME_NETWORK_SUFFIX;

        /** Folder with datastore preferences. */
        public static final String DATASTORE_PREFS_DIRECTORY = "datastore";

        /** Path to external storage */
        public static final File EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory();

        /** Manual backups go here. */
        public static final File EXTERNAL_WALLET_BACKUP_DIR = new File(Environment
                .getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);

        /** Filename of the manual key backup (old format, can only be read). */
        public static final String EXTERNAL_WALLET_KEY_BACKUP = "dash-wallet-keys" + FILENAME_NETWORK_SUFFIX;

        /** Filename of the manual wallet backup. */
        public static final String EXTERNAL_WALLET_BACKUP = "dash-wallet-backup" + FILENAME_NETWORK_SUFFIX;

        /** Filename of the block store for storing the chain. */
        public static final String BLOCKCHAIN_FILENAME = "blockchain" + FILENAME_NETWORK_SUFFIX;

        /** Filename of the block store for storing the headers. */
        public static final String HEADERS_FILENAME = "headers" + FILENAME_NETWORK_SUFFIX;

        /** Filename of the block checkpoints file. */
        public static final String CHECKPOINTS_FILENAME = "checkpoints" + FILENAME_NETWORK_SUFFIX + ".txt";

        /** Filename of the bootstrap masternode list diff file. */
        public static final String MNLIST_BOOTSTRAP_FILENAME = "mnlistdiff" + FILENAME_NETWORK_SUFFIX + ".dat";

        /** Filename of the bootstrap qrinfo file. */
        public static final String QRINFO_BOOTSTRAP_FILENAME = "qrinfo" + FILENAME_NETWORK_SUFFIX + ".dat";

        /** Filename of the fees files. */
        public static final String FEES_FILENAME = "fees" + FILENAME_NETWORK_SUFFIX + ".txt";

        /** Filename of the file containing Electrum servers. */
        public static final String ELECTRUM_SERVERS_FILENAME = "electrum-servers.txt";
    }

    /** Maximum size of backups. Files larger will be rejected. */
    public static final long BACKUP_MAX_CHARS = 10000000;

    /** URL to fetch version alerts from. */
    public static final HttpUrl VERSION_URL = HttpUrl.parse("https://wallet.schildbach.de/version");
    /** URL to fetch dynamic fees from. */
    public static final HttpUrl DYNAMIC_FEES_URL = HttpUrl.parse("https://wallet.schildbach.de/fees");

    /** MIME type used for transmitting single transactions. */
    public static final String MIMETYPE_TRANSACTION = "application/x-dashtx";

    /** MIME type used for transmitting wallet backups. */
    public static final String MIMETYPE_WALLET_BACKUP = "application/x-dash-wallet-backup";

    /** Number of confirmations until a transaction is fully confirmed. */
    public static final int MAX_NUM_CONFIRMATIONS = 6;

    /** User-agent to use for network access. */
    public static final String USER_AGENT = "Dash Wallet";

    /** Default currency to use if all default mechanisms fail. */
    public static final String DEFAULT_EXCHANGE_CURRENCY = "USD";

    /** Recipient e-mail address for reports. */
    public static final String REPORT_EMAIL = BuildConfig.SUPPORT_EMAIL;

    /** Subject line for manually reported issues. */
    public static final String REPORT_SUBJECT_BEGIN = "Android Dash Wallet:  ";
    public static final String REPORT_SUBJECT_ISSUE = "Reported issue";

    /** Subject line for crash reports. */
    public static final String REPORT_SUBJECT_CRASH = "Crash report";

    public static final int ADDRESS_FORMAT_GROUP_SIZE = 4;
    public static final int ADDRESS_FORMAT_LINE_SIZE = 12;
    public static final int ADDRESS_ROW_FORMAT_LINE_SIZE = 20;
    public static final int ADDRESS_FORMAT_FIRST_SECTION_SIZE = 12;
    public static final int ADDRESS_FORMAT_LAST_SECTION_SIZE = 3;
    public static final String ADDRESS_FORMAT_SECTION_SEPARATOR = "…";

    public static final MonetaryFormat LOCAL_FORMAT = new MonetaryFormat().noCode().minDecimals(2).optionalDecimals();
    public static final BaseEncoding HEX = BaseEncoding.base16().lowerCase();

    public static final String SOURCE_URL = "https://github.com/dashevo/dash-wallet";
    public static final String BINARY_URL = "https://github.com/dashevo/dash-wallet/releases";
    public static final String MARKET_APP_URL = "market://details?id=%s";
    public static final String WEBMARKET_APP_URL = "https://play.google.com/store/apps/details?id=%s";

    public static final int PEER_DISCOVERY_TIMEOUT_MS = 10 * (int) DateUtils.SECOND_IN_MILLIS;
    public static final int PEER_TIMEOUT_MS = 15 * (int) DateUtils.SECOND_IN_MILLIS;

    public static final long LAST_USAGE_THRESHOLD_JUST_MS = DateUtils.HOUR_IN_MILLIS;
    public static final long LAST_USAGE_THRESHOLD_RECENTLY_MS = 2 * DateUtils.DAY_IN_MILLIS;
    public static final long LAST_USAGE_THRESHOLD_INACTIVE_MS = 4 * DateUtils.WEEK_IN_MILLIS;

    public static final long DELAYED_TRANSACTION_THRESHOLD_MS = 2 * DateUtils.HOUR_IN_MILLIS;

    public static final int SDK_DEPRECATED_BELOW = Build.VERSION_CODES.M;

    public static final int MEMORY_CLASS_LOWEND = 64;

    public static final int NOTIFICATION_ID_CONNECTED = 0;
    public static final int NOTIFICATION_ID_COINS_RECEIVED = 1;
    public static final int NOTIFICATION_ID_INACTIVITY = 2;
    public static final int NOTIFICATION_ID_BLOCKCHAIN_SYNC = 3;
    public static final int NOTIFICATION_ID_UPGRADE_WALLET = 4;

    public static String NOTIFICATION_CHANNEL_ID_TRANSACTIONS = "dash.notifications.transactions";
    public static String NOTIFICATION_CHANNEL_ID_ONGOING = "dash.notifications.ongoing";
    public static String NOTIFICATION_CHANNEL_ID_GENERIC = "dash.notifications.generic";

    /** Desired number of scrypt iterations for deriving the spending PIN */
    public static final int SCRYPT_ITERATIONS_TARGET = 65536;
    public static final int SCRYPT_ITERATIONS_TARGET_LOWRAM = 32768/2;

    /** Default ports for Electrum servers */
    public static final int ELECTRUM_SERVER_DEFAULT_PORT_TCP = NETWORK_PARAMETERS.getId()
            .equals(NetworkParameters.ID_MAINNET) ? 50001 : 51001;
    public static final int ELECTRUM_SERVER_DEFAULT_PORT_TLS = NETWORK_PARAMETERS.getId()
            .equals(NetworkParameters.ID_MAINNET) ? 50002 : 51002;

    /** Shared HTTP client, can reuse connections */
    public static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(new HttpLoggingInterceptor(
                    new HttpLoggingInterceptor.Logger() {
                        @Override
                        public void log(final String message) {
                            log.debug(message);
                        }
                    }).setLevel(HttpLoggingInterceptor.Level.BASIC))
            .build();

    private static final Logger log = LoggerFactory.getLogger(Constants.class);

    //Dash Specific
    public static long EARLIEST_HD_SEED_CREATION_TIME = 1427610960L;

    public static String WALLET_URI_SCHEME = "dashwallet";
    public static String ANYPAY_SCHEME = "pay";
    public static String DASH_SCHEME = "dash";

    public static boolean ENABLE_ZERO_FEES = false; //Enable Zero Fee's on TestNet only.

    //Wallet Lock Preferences
    public static final String WALLET_LOCK_PREFS_NAME = "wallet_lock_prefs";

    //BIP44 Support
    public static final ImmutableList<ChildNumber> BIP44_PATH;

    //Backup Warnings (true = both seed and backup file, false = seed only)
    public static final boolean SUPPORT_BOTH_BACKUP_WARNINGS = false;
}
