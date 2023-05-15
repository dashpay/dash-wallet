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

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.sqlite.SQLiteException;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.hilt.work.HiltWorkerFactory;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.multidex.MultiDexApplication;
import androidx.work.WorkManager;

import com.google.common.base.Stopwatch;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBag;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.crypto.IKey;
import org.bitcoinj.crypto.LinuxSecureRandom;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.AuthenticationKeyChain;
import org.bitcoinj.wallet.CoinSelector;
import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletExtension;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension;
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage;
import org.dash.wallet.common.AutoLogoutTimerHandler;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.InteractionAwareActivity;
import org.dash.wallet.common.WalletDataProvider;
import org.dash.wallet.common.services.LeftoverBalanceException;
import org.dash.wallet.common.transactions.filters.TransactionFilter;
import org.dash.wallet.common.transactions.TransactionWrapper;
import org.dash.wallet.features.exploredash.ExploreSyncWorker;
import org.dash.wallet.common.services.TransactionMetadataProvider;
import org.dash.wallet.features.exploredash.di.ExploreDashModule;
import org.dash.wallet.integration.coinbase_integration.service.CoinBaseClientConstants;
import de.schildbach.wallet.transactions.MasternodeObserver;
import de.schildbach.wallet.ui.buy_sell.LiquidClient;
import org.dash.wallet.integration.uphold.api.UpholdClient;
import org.dash.wallet.integration.uphold.data.UpholdConstants;
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig;
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeBalanceCondition;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.android.LogcatAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import dagger.hilt.android.HiltAndroidApp;
import org.dash.wallet.common.data.BlockchainState;
import de.schildbach.wallet.data.BlockchainStateDao;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;
import de.schildbach.wallet.service.BlockchainSyncJobService;
import de.schildbach.wallet.transactions.TransactionWrapperHelper;
import de.schildbach.wallet.service.RestartService;
import de.schildbach.wallet.transactions.WalletBalanceObserver;
import de.schildbach.wallet.transactions.WalletObserver;
import de.schildbach.wallet.transactions.WalletMostRecentTransactionsObserver;
import de.schildbach.wallet.ui.preference.PinRetryController;
import de.schildbach.wallet.security.SecurityGuard;
import de.schildbach.wallet.util.AllowLockTimeRiskAnalysis;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.MnemonicCodeExt;
import de.schildbach.wallet_test.BuildConfig;
import de.schildbach.wallet_test.R;
import kotlin.Deprecated;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;

/**
 * @author Andreas Schildbach
 */
@HiltAndroidApp
public class WalletApplication extends MultiDexApplication
        implements androidx.work.Configuration.Provider, AutoLogoutTimerHandler, WalletDataProvider {
    private static WalletApplication instance;
    private Configuration config;
    private ActivityManager activityManager;
    private final List<Function0<Unit>> wipeListeners = new ArrayList<>();

    private boolean basicWalletInitalizationFinished = false;

    private Intent blockchainServiceIntent;

    private File walletFile;
    private Wallet wallet;
    private AuthenticationGroupExtension authenticationGroupExtension;
    private PackageInfo packageInfo;

    public static final String ACTION_WALLET_REFERENCE_CHANGED = WalletApplication.class.getPackage().getName()
            + ".wallet_reference_changed";

    public static final int VERSION_CODE_SHOW_BACKUP_REMINDER = 205;

    public static final long TIME_CREATE_APPLICATION = System.currentTimeMillis();

    private static final Logger log = LoggerFactory.getLogger(WalletApplication.class);

    private static final int BLOCKCHAIN_SYNC_JOB_ID = 1;

    public boolean myPackageReplaced = false;

    public Activity currentActivity;

    private AutoLogout autoLogout;

    @Inject
    RestartService restartService;
    @Inject
    HiltWorkerFactory workerFactory;
    @Inject
    BlockchainStateDao blockchainStateDao;
    @Inject
    CrowdNodeConfig crowdNodeConfig;

    @Inject
    TransactionMetadataProvider transactionMetadataProvider;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        instance = this;
    }

    public boolean walletFileExists() {
        return walletFile.exists();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initLogging();
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        log.info("WalletApplication.onCreate()");
        config = new Configuration(PreferenceManager.getDefaultSharedPreferences(this), getResources());
        autoLogout = new AutoLogout(config);
        authenticationGroupExtension = new AuthenticationGroupExtension(Constants.NETWORK_PARAMETERS);
        autoLogout.registerDeviceInteractiveReceiver(this);
        registerActivityLifecycleCallbacks(new ActivitiesTracker() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
                currentActivity = activity;
                super.onActivityCreated(activity, savedInstanceState);
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                currentActivity = activity;
                super.onActivityStarted(activity);
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                currentActivity = activity;
                super.onActivityResumed(activity);
            }

            @Override
            protected void onStartedFirst(Activity activity) {

            }

            @Override
            protected void onStartedAny(boolean isTheFirstOne, Activity activity) {
                super.onStartedAny(isTheFirstOne, activity);
                // force restart if the app was updated
                // this ensures that v6.x or previous will go through the PIN upgrade process
                if (!BuildConfig.DEBUG && myPackageReplaced) {
                    log.info("restarting app due to upgrade");
                    myPackageReplaced = false;
                    restartService.performRestart(activity, true, true);
                }
            }

            @Override
            protected void onStoppedLast() {
                autoLogout.setAppWentBackground(true);
                if (config.getAutoLogoutEnabled() && config.getAutoLogoutMinutes() == 0) {
                    sendBroadcast(new Intent(InteractionAwareActivity.FORCE_FINISH_ACTION));
                }
            }
        });
        walletFile = getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);
        if (walletFileExists()) {
            fullInitialization();
        }

        CrashReporter.init(getCacheDir());

        Threading.uncaughtExceptionHandler = (thread, throwable) -> {
            log.info("dashj uncaught exception", throwable);
            CrashReporter.saveBackgroundTrace(throwable, packageInfo);
        };

        try {
            syncExploreData();
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            CrashReporter.saveBackgroundTrace(ex, packageInfo);
        }

        resetBlockchainSyncProgress();
    }

    private void syncExploreData() {
        boolean isMainNet = Constants.NETWORK_PARAMETERS.getId().equals(NetworkParameters.ID_MAINNET);
        ExploreSyncWorker.Companion.run(getApplicationContext(), isMainNet);
    }

    public void fullInitialization() {
        initEnvironment();
        loadWalletFromProtobuf();
    }

    public void initEnvironmentIfNeeded() {
        if (!basicWalletInitalizationFinished) {
            initEnvironment();
        }
    }

    private void initEnvironment() {
        basicWalletInitalizationFinished = true;

        new LinuxSecureRandom(); // init proper random number generator

        if (!Constants.IS_PROD_BUILD) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().permitDiskReads()
                    .permitDiskWrites().penaltyLog().build());
        }

        Threading.throwOnLockCycles();
        org.bitcoinj.core.Context.enableStrictMode();
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

        log.info("=== starting app using configuration: {}, {}", BuildConfig.FLAVOR,
                Constants.NETWORK_PARAMETERS.getId());

        packageInfo = packageInfoFromContext(this);

        MnemonicCodeExt.initMnemonicCode(this);

        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        blockchainServiceIntent = new Intent(this, BlockchainServiceImpl.class);
    }

    public void setWallet(Wallet newWallet) {
        this.wallet = newWallet;
        // TODO: move to a wallet creation class
        if (!wallet.hasKeyChain(Constants.BIP44_PATH)) {
            wallet.addKeyChain(Constants.BIP44_PATH);
        }
        if (!authenticationGroupExtension.hasKeyChains()) {
            authenticationGroupExtension.addKeyChains(
                    wallet.getParams(),
                    wallet.getKeyChainSeed(),
                    EnumSet.of(
                            AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER,
                            AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING,
                            AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR,
                            AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR
                    )
            );

            authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER);
            authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING);
            authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR);
            authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR);
        }
    }

    public void saveWalletAndFinalizeInitialization() {
        saveWallet();
        backupWallet();

        config.armBackupReminder();

        finalizeInitialization();
    }

    public void finalizeInitialization() {
        wallet.getContext().initDash(true, true, Constants.SYNC_FLAGS, Constants.VERIFY_FLAGS);

        if (config.versionCodeCrossed(packageInfo.versionCode, VERSION_CODE_SHOW_BACKUP_REMINDER)
                && !wallet.getImportedKeys().isEmpty()) {
            log.info("showing backup reminder once, because of imported keys being present");
            config.armBackupReminder();
        }

        config.updateLastVersionCode(packageInfo.versionCode);

        if (config.getTaxCategoryInstallTime() == 0) {
            config.setTaxCategoryInstallTime(System.currentTimeMillis());
        }

        afterLoadWallet();

        cleanupFiles();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels();
        }

        initUphold();
        initCoinbase();
    }

    private void initUphold() {
        //Uses Sha256 hash of excerpt of xpub as Uphold authentication salt
        String xpub = wallet.getWatchingKey().serializePubB58(Constants.NETWORK_PARAMETERS);
        byte[] xpubExcerptHash = Sha256Hash.hash(xpub.substring(4, 15).getBytes());
        String authenticationHash = Sha256Hash.wrap(xpubExcerptHash).toString();

        UpholdConstants.CLIENT_ID = BuildConfig.UPHOLD_CLIENT_ID;
        UpholdConstants.CLIENT_SECRET = BuildConfig.UPHOLD_CLIENT_SECRET;
        UpholdConstants.initialize(Constants.NETWORK_PARAMETERS.getId().contains("test"));
        UpholdClient.init(getApplicationContext(), authenticationHash);
        LiquidClient.Companion.init(getApplicationContext(), authenticationHash);
    }

    private void initCoinbase() {
        CoinBaseClientConstants.CLIENT_ID = BuildConfig.COINBASE_CLIENT_ID;
        CoinBaseClientConstants.CLIENT_SECRET = BuildConfig.COINBASE_CLIENT_SECRET;
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannels() {
        // Transactions
        createNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS,
                R.string.notification_transactions_channel_name,
                R.string.notification_transactions_channel_description,
                NotificationManager.IMPORTANCE_HIGH);

        // Synchronization
        createNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_ONGOING,
                R.string.notification_synchronization_channel_name,
                R.string.notification_synchronization_channel_description,
                NotificationManager.IMPORTANCE_LOW);

        // Generic notifications
        createNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_GENERIC,
                R.string.notification_generic_channel_name,
                R.string.notification_generic_channel_description,
                NotificationManager.IMPORTANCE_HIGH);

        // Push notifications
        createNotificationChannel(getString(R.string.fcm_notification_channel_id),
                R.string.notification_push_channel_name,
                R.string.notification_push_channel_description,
                NotificationManager.IMPORTANCE_HIGH);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannel(String channelId, @StringRes int channelName,
                                           @StringRes int channelDescription, int importance) {
        CharSequence name = getString(channelName);
        String description = getString(channelDescription);

        NotificationChannel channel = new NotificationChannel(channelId, name, importance);
        channel.setDescription(description);

        if (Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS.equals(channelId)) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
            Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.coins_received);
            channel.setSound(soundUri, attributes);
        }

        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }


    private void afterLoadWallet() {
        wallet.autosaveToFile(walletFile, Constants.Files.WALLET_AUTOSAVE_DELAY_MS, TimeUnit.MILLISECONDS, null);

        // clean up spam
        try {
            wallet.cleanup();
        } catch (IllegalStateException x) {
            //Catch an inconsistent exception here and reset the blockchain.  This is for loading older wallets that had
            //txes with fees that were too low or dust that were stuck and could not be sent.  In a later version
            //the fees were fixed, then those stuck transactions became inconsistant and the exception is thrown.
            if (x.getMessage().contains("Inconsistent spent tx:")) {
                File blockChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME);
                blockChainFile.delete();
            } else throw x;
        }

        // make sure there is at least one recent backup
        if (!getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF).exists())
            backupWallet();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void initLogging() {
        // create log dir
        final File logDir = new File(getFilesDir(), "log");
        logDir.mkdir();

        // migrate old logs
        final File oldLogDir = getDir("log", MODE_PRIVATE);
        if (oldLogDir.exists()) {
            //noinspection ConstantConditions
            for (final File logFile : oldLogDir.listFiles())
                if (logFile.isFile() && logFile.length() > 0)
                    logFile.renameTo(new File(logDir, logFile.getName()));
            oldLogDir.delete();
        }

        final File logFile = new File(logDir, "wallet.log");

        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        final PatternLayoutEncoder filePattern = new PatternLayoutEncoder();
        filePattern.setContext(context);
        filePattern.setPattern("%d{HH:mm:ss,UTC} [%thread] %logger{0} - %msg%n");
        filePattern.start();

        final RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<ILoggingEvent>();
        fileAppender.setContext(context);
        fileAppender.setFile(logFile.getAbsolutePath());

        final TimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new TimeBasedRollingPolicy<ILoggingEvent>();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(fileAppender);
        rollingPolicy.setFileNamePattern(logDir.getAbsolutePath() + "/wallet.%d{yyyy-MM-dd,UTC}.log.gz");
        rollingPolicy.setMaxHistory(7);
        rollingPolicy.start();


        fileAppender.setEncoder(filePattern);
        fileAppender.setRollingPolicy(rollingPolicy);
        fileAppender.start();

        final PatternLayoutEncoder logcatTagPattern = new PatternLayoutEncoder();
        logcatTagPattern.setContext(context);
        logcatTagPattern.setPattern("%logger{0}");
        logcatTagPattern.start();

        final PatternLayoutEncoder logcatPattern = new PatternLayoutEncoder();
        logcatPattern.setContext(context);
        logcatPattern.setPattern("[%thread] %msg%n");
        logcatPattern.start();

        final LogcatAppender logcatAppender = new LogcatAppender();
        logcatAppender.setContext(context);
        logcatAppender.setTagEncoder(logcatTagPattern);
        logcatAppender.setEncoder(logcatPattern);
        logcatAppender.start();

        final ch.qos.logback.classic.Logger log = context.getLogger(Logger.ROOT_LOGGER_NAME);
        log.addAppender(fileAppender);
        log.addAppender(logcatAppender);
        log.setLevel(Level.INFO);
    }

    @Deprecated(message = "Inject Configuration instead")
    public Configuration getConfiguration() {
        return config;
    }

    @Override
    public Wallet getWallet() {
        return wallet;
    }

    @Override
    @NonNull
    public TransactionBag getTransactionBag() {
        if (wallet == null) {
            throw new IllegalStateException("Wallet is null");
        }

        return wallet;
    }

    private void loadWalletFromProtobuf() {
        FileInputStream walletStream = null;

        try {
            final Stopwatch watch = Stopwatch.createStarted();
            walletStream = new FileInputStream(walletFile);
            wallet = new WalletProtobufSerializer().readWallet(walletStream, authenticationGroupExtension);

            if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS))
                throw new UnreadableWalletException("bad wallet network parameters: " + wallet.getParams().getId());

            log.info("wallet loaded from: '{}', took {}", walletFile, watch);
        } catch (final FileNotFoundException x) {
            log.error("problem loading wallet", x);

            Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

            wallet = restoreWalletFromBackup();
        } catch (final UnreadableWalletException x) {
            log.error("problem loading wallet", x);

            Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

            wallet = restoreWalletFromBackup();
        } finally {
            if (walletStream != null) {
                try {
                    walletStream.close();
                } catch (final IOException x) {
                    // swallow
                }
            }
        }

        wallet.setRiskAnalyzer(new AllowLockTimeRiskAnalysis.OfflineAnalyzer(config.getBestHeightEver(), System.currentTimeMillis()/1000));

        if (!wallet.isConsistent()) {
            Toast.makeText(this, "inconsistent wallet: " + walletFile, Toast.LENGTH_LONG).show();

            wallet = restoreWalletFromBackup();
        }

        if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS))
            throw new Error("bad wallet network parameters: " + wallet.getParams().getId());

        finalizeInitialization();
    }

    private Wallet restoreWalletFromBackup() {
        InputStream is = null;

        try {
            is = openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);
            final Wallet wallet = new WalletProtobufSerializer().readWallet(is, true, getWalletExtensions());

            if (!wallet.isConsistent())
                throw new Error("inconsistent backup");

            wallet.addKeyChain(Constants.BIP44_PATH);

            resetBlockchain();

            Toast.makeText(this, R.string.toast_wallet_reset, Toast.LENGTH_LONG).show();

            log.info("wallet restored from backup: '" + Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "'");

            return wallet;
        } catch (final IOException x) {
            throw new Error("cannot read backup", x);
        } catch (final UnreadableWalletException x) {
            throw new Error("cannot read backup", x);
        } finally {
            try {
                is.close();
            } catch (final IOException x) {
                // swallow
            }
        }
    }

    public void saveWallet() {
        try {
            protobufSerializeWallet(wallet);
        } catch (final IOException x) {
            throw new RuntimeException(x);
        }
    }

    private void protobufSerializeWallet(final Wallet wallet) throws IOException {
        final Stopwatch watch = Stopwatch.createStarted();
        wallet.saveToFile(walletFile);
        watch.stop();

        log.info("wallet saved to: '{}', took {}", walletFile, watch);
    }

    public void backupWallet() {
        final Stopwatch watch = Stopwatch.createStarted();
        final Protos.Wallet.Builder builder = new WalletProtobufSerializer().walletToProto(wallet).toBuilder();

        // strip redundant
        builder.clearTransaction();
        builder.clearLastSeenBlockHash();
        builder.setLastSeenBlockHeight(-1);
        builder.clearLastSeenBlockTimeSecs();
        final Protos.Wallet walletProto = builder.build();

        OutputStream os = null;

        try {
            os = openFileOutput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, Context.MODE_PRIVATE);
            walletProto.writeTo(os);
            watch.stop();
            log.info("wallet backed up to: '{}', took {}", Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, watch);
        } catch (final IOException x) {
            log.error("problem writing wallet backup", x);
        } finally {
            try {
                os.close();
            } catch (final IOException x) {
                // swallow
            }
        }
    }

    private void cleanupFiles() {
        for (final String filename : fileList()) {
            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_BASE58)
                    || filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + '.')
                    || filename.endsWith(".tmp")) {
                final File file = new File(getFilesDir(), filename);
                log.info("removing obsolete file: '{}'", file);
                file.delete();
            }
        }
    }

    private void clearDatastorePrefs() {
        final File folder = new File(getFilesDir(), Constants.Files.DATASTORE_PREFS_DIRECTORY);

        if (folder.isDirectory()) {
            log.info("removing datastore preferences");
            final File[] files = folder.listFiles();

            if (files != null) {
                for (File file: files) {
                    file.delete();
                }
            }
        }
    }

    private void clearExploreConfig() {
        SharedPreferences prefs = getSharedPreferences(ExploreDashModule.Companion.getPREFERENCES_FILENAME(), Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    private void clearWebCookies() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
    }

    public void startBlockchainService(final boolean cancelCoinsReceived) {
        // hack for Android P bug https://issuetracker.google.com/issues/113122354
        ActivityManager activityManager = (ActivityManager) getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = activityManager != null ? activityManager.getRunningAppProcesses() : null;
        if (runningAppProcesses != null) {
            int importance = runningAppProcesses.get(0).importance;
            if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)

                if (cancelCoinsReceived) {
                    Intent blockchainServiceCancelCoinsReceivedIntent = new Intent(BlockchainService.ACTION_CANCEL_COINS_RECEIVED, null,
                            this, BlockchainServiceImpl.class);
                    startService(blockchainServiceCancelCoinsReceivedIntent);
                } else {
                    startService(blockchainServiceIntent);
                }
        }
    }

    public void stopBlockchainService() {
        stopService(blockchainServiceIntent);
    }

    public void resetBlockchainState() {
        Executors.newSingleThreadExecutor().execute(() -> {
            blockchainStateDao.save(new BlockchainState(true));
        });
    }

    public void resetBlockchain() {
        // implicitly stops blockchain service
        resetBlockchainState();
        Intent blockchainServiceResetBlockchainIntent = new Intent(BlockchainService.ACTION_RESET_BLOCKCHAIN, null, this,
                BlockchainServiceImpl.class);
        startService(blockchainServiceResetBlockchainIntent);
    }

    private void resetBlockchainSyncProgress() {
        Executors.newSingleThreadExecutor().execute(() -> {
            BlockchainState blockchainState;

            try {
                 blockchainState = blockchainStateDao.loadSync();
            } catch (SQLiteException ex) {
                blockchainState = null;
            }

            if (blockchainState != null) {
                blockchainState.setPercentageSync(0);
                blockchainStateDao.save(blockchainState);
            }
        });
    }

    public void replaceWallet(final Wallet newWallet) {
        resetBlockchain();
        if (wallet != null) {
            wallet.shutdownAutosaveAndWait();
        }

        wallet = newWallet;
        config.maybeIncrementBestChainHeightEver(newWallet.getLastBlockSeenHeight());
        afterLoadWallet();

        final Intent broadcast = new Intent(ACTION_WALLET_REFERENCE_CHANGED);
        broadcast.setPackage(getPackageName());
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    public void processDirectTransaction(final Transaction tx) throws VerificationException {
        if (wallet.isTransactionRelevant(tx)) {
            wallet.receivePending(tx, null);
            broadcastTransaction(tx);
        }
    }

    public void broadcastTransaction(final Transaction tx) {
        final Intent intent = new Intent(BlockchainService.ACTION_BROADCAST_TRANSACTION, null, this,
                BlockchainServiceImpl.class);
        intent.putExtra(BlockchainService.ACTION_BROADCAST_TRANSACTION_HASH, tx.getHash().getBytes());
        startService(intent);
    }

    public static PackageInfo packageInfoFromContext(final Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (final NameNotFoundException x) {
            throw new RuntimeException(x);
        }
    }

    public PackageInfo packageInfo() {
        return packageInfo;
    }

    public final String applicationPackageFlavor() {
        final String packageName = getPackageName();
        final int index = packageName.lastIndexOf('_');

        if (index != -1)
            return packageName.substring(index + 1);
        else
            return null;
    }

    public static String httpUserAgent(final String versionName) {
        final VersionMessage versionMessage = new VersionMessage(Constants.NETWORK_PARAMETERS, 0);
        versionMessage.appendToSubVer(Constants.USER_AGENT, versionName, null);
        return versionMessage.subVer;
    }

    public String httpUserAgent() {
        return httpUserAgent(packageInfo().versionName);
    }

    public boolean isLowRamDevice() {
        if (activityManager == null)
            return false;

        return activityManager.isLowRamDevice();
    }

    public int maxConnectedPeers() {
        return isLowRamDevice() ? 4 : 6;
    }

    public static void scheduleStartBlockchainService(final Context context) {
        scheduleStartBlockchainService(context, false);
    }

    public void cancelScheduledStartBlockchainService() {
        scheduleStartBlockchainService(this, true);
    }

    @SuppressLint("NewApi")
    public static void scheduleStartBlockchainService(final Context context, Boolean cancelOnly) {
        final Configuration config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context),
                context.getResources());
        final long lastUsedAgo = config.getLastUsedAgo();

        // apply some backoff
        final long alarmInterval;
        if (lastUsedAgo < Constants.LAST_USAGE_THRESHOLD_JUST_MS)
            alarmInterval = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
        else if (lastUsedAgo < Constants.LAST_USAGE_THRESHOLD_RECENTLY_MS)
            alarmInterval = AlarmManager.INTERVAL_HALF_DAY;
        else
            alarmInterval = AlarmManager.INTERVAL_DAY;

        final long alarmIntervalMinutes = TimeUnit.MILLISECONDS.toMinutes(alarmInterval);

        log.info("last used {} minutes ago, rescheduling blockchain sync in roughly {} minutes",
                lastUsedAgo / DateUtils.MINUTE_IN_MILLIS, alarmInterval / DateUtils.MINUTE_IN_MILLIS);

        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent alarmIntent;

        Intent serviceIntent = new Intent(context, BlockchainServiceImpl.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            serviceIntent.putExtra(BlockchainServiceImpl.START_AS_FOREGROUND_EXTRA, true);
            alarmIntent = PendingIntent.getForegroundService(context, 0, serviceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            alarmIntent = PendingIntent.getService(context, 0, serviceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        alarmManager.cancel(alarmIntent);

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O || Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1) {
            log.info("custom sync scheduling with JobScheduler for Android 8 and 8.1");
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (cancelOnly) {
                jobScheduler.cancel(BLOCKCHAIN_SYNC_JOB_ID);
                return;
            }
            JobInfo pendingJob = jobScheduler.getPendingJob(BLOCKCHAIN_SYNC_JOB_ID);
            if (pendingJob == null || pendingJob.getIntervalMillis() != alarmInterval) {
                ComponentName jobService = new ComponentName(context, BlockchainSyncJobService.class);
                JobInfo jobInfo = new JobInfo.Builder(BLOCKCHAIN_SYNC_JOB_ID, jobService)
                        .setPeriodic(alarmInterval)
                        .setPersisted(true)
                        .build();
                int scheduleResult = jobScheduler.schedule(jobInfo);
                log.info("scheduling blockchain sync job with interval of {} minutes, result: {}", alarmIntervalMinutes, scheduleResult);
            } else {
                log.info("blockchain sync job already scheduled with interval of {} minutes", alarmIntervalMinutes);
            }
        } else if (!cancelOnly) {
            // workaround for no inexact set() before KitKat
            final long now = System.currentTimeMillis();
            alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, now + alarmInterval, AlarmManager.INTERVAL_DAY,
                    alarmIntent);
        }
    }

    /**
     * Removes all the data and restarts the app showing onboarding screen.
     */
    public void triggerWipe() {
        log.info("Removing all the data and restarting the app.");
        startService(new Intent(BlockchainService.ACTION_WIPE_WALLET, null, this, BlockchainServiceImpl.class));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void shutdownAndDeleteWallet() {
        if (walletFile.exists()) {
            wallet.shutdownAutosaveAndWait();
            walletFile.delete();
        }
    }

    public void finalizeWipe() {
        cancelScheduledStartBlockchainService();
        WorkManager.getInstance(this.getApplicationContext()).cancelAllWork();
        shutdownAndDeleteWallet();
        cleanupFiles();
        config.clear();
        clearExploreConfig();
        clearDatastorePrefs();
        clearWebCookies();
        notifyWalletWipe();
        PinRetryController.getInstance().clearPinFailPrefs();
        MnemonicCodeExt.clearWordlistPath(this);
        try {
            new SecurityGuard().removeKeys();
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            log.warn("error occurred when removing security keys", e);
        }

        File walletBackupFile = getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);
        if (walletBackupFile.exists()) {
            walletBackupFile.delete();
        }
        // clear data on wallet reset
        transactionMetadataProvider.clear();
        // wallet must be null for the OnboardingActivity flow
        log.info("removing wallet from memory during wipe");
        wallet = null;
        clearExtensions();
    }

    private void notifyWalletWipe() {
        for (Function0<Unit> listener : wipeListeners) {
            listener.invoke();
        }
    }

    @NonNull
    @Override
    public androidx.work.Configuration getWorkManagerConfiguration() {
        return new androidx.work.Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(Log.VERBOSE)
                .build();
    }

    @Deprecated(message = "Inject instead")
    public static WalletApplication getInstance() {
        return instance;
    }

    public AutoLogout getAutoLogout() {
        return autoLogout;
    }

    @Override
    public void resetAutoLogoutTimer() {
        autoLogout.resetTimerIfActive();
    }

    @Override
    public void startAutoLogoutTimer() {
        autoLogout.startTimer();
    }

    @Override
    public void stopAutoLogoutTimer() {
        autoLogout.stopTimer();
    }

    @NotNull
    @Override
    public Address freshReceiveAddress() {
        return wallet.freshReceiveAddress();
    }

    @NotNull
    public Coin getWalletBalance() {
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED);
    }

    @NonNull
    @Override
    public Flow<Coin> observeBalance(
        @NonNull Wallet.BalanceType balanceType,
        @Nullable CoinSelector coinSelector
    ) {
        if (wallet == null) {
            return FlowKt.emptyFlow();
        }

        return new WalletBalanceObserver(wallet, balanceType, coinSelector).observe();
    }

    @NonNull
    @Override
    public Flow<Transaction> observeTransactions(
        boolean withConfidence,
        @NonNull TransactionFilter... filters
    ) {
        if (wallet == null) {
            return FlowKt.emptyFlow();
        }

        return new WalletObserver(wallet).observeTransactions(withConfidence, filters);
    }

    @NonNull
    @Override
    public Flow<Unit> observeWalletChanged() {
        if (wallet == null) {
            return FlowKt.emptyFlow();
        }

        return new WalletObserver(wallet).observeWalletChanged();
    }

    @NonNull
    @Override
    public Flow<List<AuthenticationKeyUsage>> observeAuthenticationKeyUsage() {
        if (wallet == null) {
            return FlowKt.emptyFlow();
        }

        return new MasternodeObserver(authenticationGroupExtension).observeAuthenticationKeyUsage();
    }

    @NonNull
    @Override
    public Collection<Transaction> getTransactions(@NonNull TransactionFilter... filters) {
        Set<Transaction> transactions = wallet.getTransactions(true);

        if (filters.length == 0) {
            return transactions;
        }

        ArrayList<Transaction> filteredTransactions = new ArrayList<>();

        for (Transaction tx : transactions) {
            for (TransactionFilter filter : filters) {
                if (filter.matches(tx)) {
                    filteredTransactions.add(tx);
                    break;
                }
            }
        }

        return filteredTransactions;
    }

    @NonNull
    @Override
    public Collection<TransactionWrapper> wrapAllTransactions(@NonNull TransactionWrapper... wrappers) {
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT);
        return TransactionWrapperHelper.INSTANCE.wrapTransactions(
                wallet.getTransactions(true),
                wrappers
        );
    }

    @NonNull
    @Override
    public Flow<Transaction> observeMostRecentTransaction() {
        if (wallet == null) {
            return FlowKt.emptyFlow();
        }
        return new WalletMostRecentTransactionsObserver(wallet).observe();
    }

    // wallets from v5.17.5 and earlier do not have a BIP44 path
    public boolean isWalletUpgradedToBIP44() {
        return wallet != null && wallet.hasKeyChain(Constants.BIP44_PATH);
    }

    @NonNull
    @Override
    public NetworkParameters getNetworkParameters() {
        return Constants.NETWORK_PARAMETERS;
    }

    @Override
    public void attachOnWalletWipedListener(@NonNull Function0<Unit> listener) {
        wipeListeners.add(listener);
    }

    @Override
    public void detachOnWalletWipedListener(@NonNull Function0<Unit> listener) {
        wipeListeners.remove(listener);
    }

    @Override
    public void checkSendingConditions(
            @Nullable Address address,
            @NonNull Coin amount
    ) throws LeftoverBalanceException {
        new CrowdNodeBalanceCondition().check(
                wallet.getBalance(Wallet.BalanceType.ESTIMATED),
                address,
                amount,
                crowdNodeConfig
        );
    }

    public void clearExtensions() {
        log.info("clearing extensions: authentication");
        authenticationGroupExtension = new AuthenticationGroupExtension(Constants.NETWORK_PARAMETERS);
    }

    public WalletExtension[] getWalletExtensions() {
        return new WalletExtension[] {authenticationGroupExtension};
    }
}
