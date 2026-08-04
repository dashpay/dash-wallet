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
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.StrictMode;
import androidx.preference.PreferenceManager;
import android.text.format.DateUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.hilt.work.HiltWorkerFactory;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.multidex.MultiDexApplication;
import androidx.work.WorkManager;

import com.appsflyer.AppsFlyerLib;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.api.client.util.Lists;
import com.google.common.base.Stopwatch;
import com.google.firebase.FirebaseApp;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBag;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.crypto.LinuxSecureRandom;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.AuthenticationKeyChain;
import org.bitcoinj.wallet.CoinSelector;
import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletEx;
import org.bitcoinj.wallet.WalletExtension;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension;
import org.bitcoinj.wallet.authentication.AuthenticationKeyUsage;
import org.conscrypt.Conscrypt;
import org.dash.wallet.common.AutoLogoutTimerHandler;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.InteractionAwareActivity;
import de.schildbach.wallet.data.WalletData;
import org.dash.wallet.common.data.WalletUIConfig;
import org.dash.wallet.common.integrations.ExchangeIntegrationProvider;
import org.dash.wallet.common.services.LeftoverBalanceException;
import org.dash.wallet.common.services.TransactionMetadataProvider;
import org.dash.wallet.common.services.analytics.AnalyticsService;
import org.dash.wallet.common.transactions.TransactionWrapperFactory;
import de.schildbach.wallet.transactions.WalletTransactionFilter;
import org.dash.wallet.common.transactions.TransactionWrapper;
import org.dash.wallet.features.exploredash.ExploreSyncWorker;
import org.dash.wallet.integrations.coinbase.service.CoinBaseClientConstants;

import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import de.schildbach.wallet.security.SecurityInitializer;
import de.schildbach.wallet.service.BlockchainStateDataProvider;
import de.schildbach.wallet.service.TxDisplayCacheService;
import de.schildbach.wallet.service.DashSystemService;
import de.schildbach.wallet.service.PackageInfoProvider;
import de.schildbach.wallet.service.WalletFactory;
import de.schildbach.wallet.service.platform.IdentityRepository;
import de.schildbach.wallet.service.platform.TopUpRepository;
import de.schildbach.wallet.service.platform.sdk.CutoverCoordinator;
import de.schildbach.wallet.service.platform.sdk.CutoverDebugReadout;
import de.schildbach.wallet.service.platform.sdk.CutoverEvidenceCollector;
import de.schildbach.wallet.service.platform.sdk.L1ShadowDebugReset;
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService;
import de.schildbach.wallet.transactions.MasternodeObserver;
import de.schildbach.wallet.transactions.WalletBalanceObserver;
import de.schildbach.wallet.ui.buy_sell.LiquidClient;
import org.dash.wallet.integrations.uphold.api.UpholdClient;
import org.dash.wallet.integrations.uphold.data.UpholdConstants;
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig;
import de.schildbach.wallet.payments.BalanceConditionBridge;
import de.schildbach.wallet.payments.MaxOutputAmountCoinSelector;
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeBalanceCondition;
import org.dash.wallet.integrations.maya.api.SwapTrackingService;
import org.dash.wallet.integrations.uphold.utils.UpholdConfig;
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
import java.security.Security;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.android.LogcatAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import dagger.hilt.android.HiltAndroidApp;
import de.schildbach.wallet.security.SecurityGuard;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;
import de.schildbach.wallet.service.BlockchainSyncJobService;
import de.schildbach.wallet.service.platform.PlatformSyncService;
import de.schildbach.wallet.transactions.TransactionWrapperHelper;
import de.schildbach.wallet.service.RestartService;
import de.schildbach.wallet.transactions.WalletObserver;
import de.schildbach.wallet.ui.dashpay.HistoryHeaderAdapter;
import de.schildbach.wallet.ui.dashpay.PlatformRepo;
import de.schildbach.wallet.transactions.WalletMostRecentTransactionsObserver;
import de.schildbach.wallet.security.PinRetryController;
import de.schildbach.wallet.util.AllowLockTimeRiskAnalysis;
import de.schildbach.wallet.util.AnrSupervisor;
import de.schildbach.wallet.util.AtomicFileWriter;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.FriendKeyChainLookahead;
import de.schildbach.wallet.util.LogMarkerFilter;
import de.schildbach.wallet.util.MnemonicCodeExt;
import de.schildbach.wallet.util.StartupBreadcrumbs;
import de.schildbach.wallet.util.WalletFileSizeGuard;
import de.schildbach.wallet.util.WalletLoadBudget;
import de.schildbach.wallet_test.BuildConfig;
import de.schildbach.wallet_test.R;
import kotlin.Deprecated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 * @author Andreas Schildbach
 */
@HiltAndroidApp
public class WalletApplication extends MultiDexApplication
        implements androidx.work.Configuration.Provider, AutoLogoutTimerHandler, WalletData {
    private static WalletApplication instance;
    private Configuration config;
    private ActivityManager activityManager;
    private final List<Function1<? super Continuation<? super Unit>, ?>> wipeListeners = new ArrayList<>();

    private boolean basicWalletInitializationFinished = false;

    private Intent blockchainServiceIntent;

    private File walletFile;
    private Wallet wallet;
    private volatile AuthenticationGroupExtension authenticationGroupExtension;
    public static final String ACTION_WALLET_REFERENCE_CHANGED = WalletApplication.class.getPackage().getName()
            + ".wallet_reference_changed";

    public static final int VERSION_CODE_SHOW_BACKUP_REMINDER = 205;

    public static final long TIME_CREATE_APPLICATION = System.currentTimeMillis();

    public static final Logger log = LoggerFactory.getLogger(WalletApplication.class);

    private static final int BLOCKCHAIN_SYNC_JOB_ID = 1;

    public boolean myPackageReplaced = false;

    /** The wallet protobuf load threw past the internal recovery (e.g. OOM on a huge wallet). */
    private volatile boolean walletLoadFailed = false;
    /** Safe mode skipped the wallet load after consecutive launch deaths (see StartupBreadcrumbs). */
    private volatile boolean walletLoadSkippedSafeMode = false;
    /** An optional startup stage failed and was skipped (catch-degrade). */
    private volatile boolean startupDegraded = false;
    /**
     * Both the primary wallet file AND the key backup are unusable — the only
     * remaining recovery is a restore from the user's recovery phrase.
     * OnboardingActivity surfaces that state explicitly.
     */
    private volatile boolean walletRecoveryFromSeedNeeded = false;

    private AutoLogout autoLogout;
    private AnrSupervisor anrSupervisor;
    private Function0 afterWipeFunction;

    @Inject
    RestartService restartService;
    @Inject
    HiltWorkerFactory workerFactory;
    @Inject
    protected AnalyticsService analyticsService;
    @Inject
    BlockchainStateDataProvider blockchainStateDataProvider;
    @Inject
    CrowdNodeConfig crowdNodeConfig;
    @Inject
    TransactionMetadataProvider transactionMetadataProvider;
    @Inject
    PlatformRepo platformRepo;
    @Inject
    IdentityRepository identityRepository;
    @Inject
    PlatformSyncService platformSyncService;
    @Inject
    de.schildbach.wallet.ui.dashpay.utils.DashPayConfig dashPayConfig;
    @Inject
    TopUpRepository topUpRepository;
    @Inject
    PackageInfoProvider packageInfoProvider;
    @Inject
    UpholdConfig upholdConfig;
    @Inject
    WalletFactory walletFactory;
    @Inject
    DashSystemService dashSystemService;
    @Inject
    WalletUIConfig walletUIConfig;
    @Inject
    SecurityInitializer securityInitializer;
    @Inject
    TxDisplayCacheService txDisplayCacheService;
    @Inject
    L1ShadowSyncService l1ShadowSyncService;
    @Inject
    CutoverCoordinator cutoverCoordinator;
    @Inject
    de.schildbach.wallet.service.platform.sdk.CutoverAutoCommitObserver cutoverAutoCommitObserver;
    @Inject
    CutoverEvidenceCollector cutoverEvidenceCollector;
    @Inject
    de.schildbach.wallet.service.platform.sdk.CutoverUiDataService cutoverUiDataService;
    @Inject
    SwapTrackingService swapTrackingService;
    private WalletBalanceObserver walletBalanceObserver;
    @Inject
    public ExchangeIntegrationProvider exchangeIntegrationProvider;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        instance = this;
    }

    public boolean walletFileExists() {
        return walletFile.exists();
    }

    /**
     * True when the wallet file exists but NO wallet object is loaded — the
     * launch is running degraded (load failure caught, or safe mode skipped
     * the load after consecutive launch deaths). OnboardingActivity must show
     * the crash-report path instead of onboarding/`wallet!!` routing.
     */
    public boolean isWalletLoadDegraded() {
        return walletLoadFailed || walletLoadSkippedSafeMode;
    }

    /** Whether safe mode (crash-loop breaker) skipped the wallet load this launch. */
    public boolean isSafeModeLaunch() {
        return walletLoadSkippedSafeMode;
    }

    /**
     * Both the primary wallet AND the key backup are unusable — only a restore
     * from the recovery phrase can bring this wallet back.
     */
    public boolean isWalletRecoveryFromSeedNeeded() {
        return walletRecoveryFromSeedNeeded;
    }

    /**
     * Preserve any existing (unusable) wallet file aside before a
     * restore-from-seed writes a fresh one at the same path — the degraded
     * recovery flow calls this so NOTHING is ever overwritten. Safe to call
     * when the file no longer exists (the oversize guard may already have
     * renamed it).
     */
    public void preserveWalletFileForRecovery() {
        if (walletFile != null && walletFile.exists()) {
            WalletFileSizeGuard.preserveAside(walletFile, "pre-seed-restore");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // CRASH-TRACE HANDLING FIRST — before the wallet load, the SDK engines,
        // logging, Firebase, everything that can die on a large wallet. Any
        // launch crash from here on is persisted to cache/crash.trace and the
        // NEXT launch offers the report dialog even with no adb and no
        // Crashlytics (the app itself is the diagnostic channel).
        CrashReporter.init(getCacheDir());
        // Numbered, PERSISTED launch-stage markers. Also decides whether this
        // launch runs in SAFE MODE (two consecutive launches died before the
        // main UI → skip the wallet load + engine starts so the app opens and
        // offers the crash report — see StartupBreadcrumbs).
        StartupBreadcrumbs.init(getFilesDir());

        runStartupStage(StartupBreadcrumbs.STAGE_LOGGING_INITIALIZED, "LOGGING_INITIALIZED", this::initLogging);
        runStartupStage(StartupBreadcrumbs.STAGE_FIREBASE_INITIALIZED, "FIREBASE_INITIALIZED", () -> {
            FirebaseApp.initializeApp(this);
            if (FirebaseApp.getApps(this).isEmpty()) {
                // Built without google-services.json (the Firebase config is intentionally optional,
                // see gradle/google-services.gradle). Initialize a placeholder app so DI-provided
                // Firebase services (auth, analytics) can be constructed; their network calls fail
                // soft inside existing error handling instead of crashing the process.
                log.warn("no Firebase config in this build; initializing placeholder FirebaseApp");
                FirebaseApp.initializeApp(this, new com.google.firebase.FirebaseOptions.Builder()
                        .setApplicationId("1:000000000000:android:0000000000000000000000")
                        .setProjectId("dash-wallet-local-build")
                        // must match Firebase's AIza[0-9A-Za-z\-_]{35} API-key format check
                        .setApiKey("AIzaSyPlaceholderLocalBuild000000000000")
                        .build());
            }
        });
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        log.info("STARTUP WalletApplication.onCreate()");
        config = new Configuration(PreferenceManager.getDefaultSharedPreferences(this));
        new Thread(this::initializeAppsFlyer).start();
        autoLogout = new AutoLogout(config);
        autoLogout.registerDeviceInteractiveReceiver(this);
        registerActivityLifecycleCallbacks(new WalletActivityTracker(this, config, autoLogout, restartService));
        walletFile = getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);
        StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_CONFIG_LOADED, "CONFIG_LOADED");
        if (walletFileExists()) {
            if (StartupBreadcrumbs.isSafeModeAdvised()) {
                // Crash-loop breaker: the last two launches died before the
                // main UI. Skip the wallet load and every engine start so the
                // app OPENS and OnboardingActivity offers the crash report;
                // the launch after this one retries a normal start.
                walletLoadSkippedSafeMode = true;
                log.warn("SAFE MODE: {} consecutive launches died before the main UI — "
                        + "skipping the wallet load so the app can open and offer a crash report",
                        StartupBreadcrumbs.SAFE_MODE_THRESHOLD);
                StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_SKIPPED_SAFE_MODE,
                        "WALLET_LOAD_SKIPPED_SAFE_MODE");
            } else {
                StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_BEGIN, "WALLET_LOAD_BEGIN",
                        "size=" + walletFile.length());
                try {
                    fullInitialization();
                } catch (final Throwable t) {
                    // The un-degradable stage failed in a way the internal
                    // recovery (restore-from-backup) did not handle: an
                    // OutOfMemoryError parsing a huge protobuf, an
                    // Error("cannot read backup"), anything unforeseen. DO NOT
                    // crash the launch and DO NOT touch the wallet file — open
                    // degraded so OnboardingActivity can offer the crash
                    // report instead of looping.
                    walletLoadFailed = true;
                    log.error("wallet load FAILED — opening degraded for crash reporting", t);
                    StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_FAILED,
                            "WALLET_LOAD_FAILED", t.getClass().getName() + ": " + t.getMessage());
                    try {
                        CrashReporter.saveBackgroundTrace(t, packageInfoProvider.getPackageInfo());
                    } catch (final Throwable ignored) {
                    }
                }
            }
        }

        // enable deadlock warnings to try to catch the cause of the stuck at "Syncing 31%"
        Threading.setUseDefaultAndroidPolicy(false);
        Threading.warnOnLockCycles();

        Threading.uncaughtExceptionHandler = (thread, throwable) -> {
            log.info("dashj uncaught exception", throwable);
            CrashReporter.saveBackgroundTrace(throwable, packageInfoProvider.getPackageInfo());
        };

        try {
            syncExploreData();
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            CrashReporter.saveBackgroundTrace(ex, packageInfoProvider.getPackageInfo());
        }

        resetBlockchainSyncProgress();
        anrSupervisor = new AnrSupervisor();
        anrSupervisor.start();

        runStartupStage(-1, "DEBUG_RECEIVERS", () -> {
            // DEBUG-only adb trigger for an L1 shadow hard reset; provably a
            // no-op in release builds (the method returns before registering).
            L1ShadowDebugReset.registerIfDebug(this, l1ShadowSyncService);
            // DEBUG-only adb trigger for a one-shot Phase 5d cutover readiness
            // readout (advisory only — can never commit a cutover).
            CutoverDebugReadout.registerIfDebug(this, cutoverCoordinator, cutoverEvidenceCollector);
        });

        // resume status polling for any DEX swaps still in flight
        runStartupStage(-1, "SWAP_TRACKING", swapTrackingService::start);

        // enable TLS 1.3 support on Android 9 and lower
        // Android 10 and above support TLS 1.3 by default
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        }

        // THE LAUNCH-COMPLETE MILESTONE. Everything that can crash-loop the app
        // is behind us, so this launch counts as a SUCCESS right now and the
        // crash-loop strike counter is cleared here — NOT inferred later from a
        // survival timer. A process death after this point (lowmemorykiller
        // reclaiming a backgrounded app, a swipe-away, a reboot) is not a launch
        // failure and must never latch safe mode.
        StartupBreadcrumbs.markLaunchComplete();
    }

    /**
     * SAFE-MODE ESCAPE HATCH — retry the wallet load that safe mode skipped,
     * in this same process, at the user's request (or automatically when the
     * degraded screen is re-entered on a warm start).
     *
     * This exists because the safe-mode verdict is taken ONCE, in
     * {@link #onCreate()}. Re-opening the app while the safe-mode process is
     * still alive does not re-run onCreate, so without this the user saw the
     * degraded screen on every open until the process happened to die — which
     * is exactly what a QA device hit. NOTHING is wiped: this is the same load
     * a normal launch performs.
     *
     * @return true when the wallet is loaded and normal routing may proceed.
     */
    public boolean retryWalletLoadAfterSafeMode() {
        if (!walletLoadSkippedSafeMode) {
            return wallet != null;
        }
        log.warn("SAFE MODE ESCAPE: retrying the skipped wallet load in-process");
        StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_SAFE_MODE_RETRY, "SAFE_MODE_RETRY");
        walletLoadSkippedSafeMode = false;
        try {
            fullInitialization();
        } catch (final Throwable t) {
            // Same handling as the onCreate load: open degraded for crash
            // reporting, never crash and never touch the wallet file.
            walletLoadFailed = true;
            log.error("safe-mode retry FAILED — staying degraded", t);
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_FAILED,
                    "WALLET_LOAD_FAILED", t.getClass().getName() + ": " + t.getMessage());
            try {
                CrashReporter.saveBackgroundTrace(t, packageInfoProvider.getPackageInfo());
            } catch (final Throwable ignored) {
            }
            return false;
        }
        if (wallet != null) {
            // The load works: the strikes that engaged safe mode were a false
            // alarm (a killed background process, not a failing launch). Clear
            // the latch on disk so no later launch engages off that history.
            StartupBreadcrumbs.clearSafeModeLatch();
            return true;
        }
        return false;
    }

    /**
     * Catch-degrade wrapper for OPTIONAL launch stages: a failure logs, records
     * a non-fatal trace (rides along in the support report) and sets the
     * degraded latch — the app still OPENS. Never used for the wallet load
     * itself, which has its own dedicated failure handling.
     */
    private void runStartupStage(final int breadcrumbStage, final String name, final Runnable stage) {
        try {
            stage.run();
            if (breadcrumbStage >= 0) {
                StartupBreadcrumbs.mark(breadcrumbStage, name);
            }
        } catch (final Throwable t) {
            startupDegraded = true;
            log.error("startup stage {} FAILED — continuing degraded", name, t);
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_DEGRADED, "DEGRADED:" + name,
                    t.getClass().getName() + ": " + t.getMessage());
            try {
                CrashReporter.saveBackgroundTrace(t, packageInfoProvider.getPackageInfo());
            } catch (final Throwable ignored) {
            }
        }
    }

    // Initialize AppsFlyer
    private void initializeAppsFlyer() {
        try {
            AppsFlyerLib appsFlyerLib = AppsFlyerLib.getInstance();
            appsFlyerLib.init(BuildConfig.APPSFLYER_ID, null, this);
            appsFlyerLib.setAppInviteOneLink(BuildConfig.APPSFLYER_TEMPLATE_ID);
            appsFlyerLib.setDebugLog(BuildConfig.DEBUG);
            appsFlyerLib.setLogLevel(com.appsflyer.AFLogger.LogLevel.VERBOSE);
            String customerId = config.getUniqueId();
            appsFlyerLib.setCustomerUserId(customerId);
            appsFlyerLib.start(this);

            // Register conversion listener after successful initialization
            appsFlyerLib.registerConversionListener(this, new com.appsflyer.AppsFlyerConversionListener() {
                @Override
                public void onConversionDataSuccess(Map<String, Object> data) {
                    log.info("AppsFlyer conversion received: {}", data);
                    if (data != null) {
                        log.info("Available keys: {}", data.keySet());
                        handleDeepLinkData(data);
                    }
                }

                @Override
                public void onConversionDataFail(String error) {
                    log.error("AppsFlyer conversion failed: {}", error);
                }

                @Override
                public void onAppOpenAttribution(Map<String, String> data) {
                    log.info("AppsFlyer app open attribution: {}", data);
                    if (data != null) {
                        log.info("Available attribution keys: {}", data.keySet());
                        Map<String, Object> objectData = new HashMap<>(data);
                        handleDeepLinkData(objectData);
                    }
                }

                @Override
                public void onAttributionFailure(String error) {
                    log.error("AppsFlyer attribution failure: {}", error);
                }

                private void handleDeepLinkData(Map<String, Object> data) {
                    String deepLinkValue = extractDeepLink(data);
                    if (deepLinkValue != null) {
                        log.info("Processing deep link: {}", deepLinkValue);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                Intent intent = new Intent(getApplicationContext(), de.schildbach.wallet.ui.InviteHandlerActivity.class);
                                intent.setData(Uri.parse(deepLinkValue));
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                            });
                    } else {
                        log.warn("No deep link found in AppsFlyer data");
                    }
                }

                private String extractDeepLink(Map<String, Object> data) {
                    // Define keys in order of preference
                    String[] possibleKeys = {"af_dp", "deep_link_value", "link", "af_sub1"};
                    for (String key : possibleKeys) {
                        Object value = data.get(key);
                        if (value instanceof String) {
                            String stringValue = (String) value;
                            if (!stringValue.trim().isEmpty()) {
                                log.info("Found deep link in key '{}': {}", key, stringValue);
                                return stringValue;
                            }
                        }
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            log.warn("Failed to initialize AppsFlyer: {}", e.getMessage());
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        walletBalanceObserver.close();
        topUpRepository.close();
        anrSupervisor.stop();
    }

    private void syncExploreData() {
        boolean isMainNet = Constants.NETWORK_PARAMETERS.getId().equals(NetworkParameters.ID_MAINNET);
        ExploreSyncWorker.Companion.run(getApplicationContext(), isMainNet);
    }

    public void fullInitialization() {
        long t0 = System.currentTimeMillis();
        initEnvironment();
        log.info("STARTUP fullInit: initEnvironment done in {}ms", System.currentTimeMillis() - t0);
        long t1 = System.currentTimeMillis();
        loadWalletFromProtobuf();
        log.info("STARTUP fullInit: loadWalletFromProtobuf done in {}ms", System.currentTimeMillis() - t1);
        log.info("STARTUP fullInit: total {}ms", System.currentTimeMillis() - t0);
    }

    public void initEnvironmentIfNeeded() {
        if (!basicWalletInitializationFinished) {
            initEnvironment();
        }
    }

    private void initEnvironment() {
        basicWalletInitializationFinished = true;

        new LinuxSecureRandom(); // init proper random number generator

        if (!Constants.IS_PROD_BUILD) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().permitDiskReads()
                    .permitDiskWrites().penaltyLog().build());
        }

        Threading.throwOnLockCycles();
        // TODO: do we need this commented out for saving
        // org.bitcoinj.core.Context.enableStrictMode();
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

        // Pre-warm RegTestParams on this single-threaded startup path.
        //
        // dashj's ZeroConfCoinSelector.isTransactionSelectable() compares a pending tx's params
        // against RegTestParams.get() for *every* pending output it considers. This selector is
        // used by getAnonymizableBalance(), send flows and ByAddressCoinSelector. RegTestParams.get()
        // lazily runs the RegTestParams constructor, which recomputes the regtest genesis X11 hash
        // and checkState()s it against a hardcoded value. When that lazy construction first happens
        // on a contended background thread (notably CoinJoin's IO coroutines, which do heavy
        // concurrent X11 hashing) a transient bad X11 result makes the checkState throw and crashes
        // balance calculation - even though this wallet never runs on regtest.
        //
        // Forcing the (synchronized, cached) construction here, before any concurrent hashing
        // begins, makes the instance compute correctly once and be reused forever, closing the race.
        try {
            RegTestParams.get();
        } catch (Throwable t) {
            log.warn("failed to pre-warm RegTestParams", t);
        }

        log.info("=== starting app using configuration: {}, {}", BuildConfig.FLAVOR,
                Constants.NETWORK_PARAMETERS.getId());

        MnemonicCodeExt.initMnemonicCode(this);

        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        blockchainServiceIntent = new Intent(this, BlockchainServiceImpl.class);
    }

    // only used by onboarding after creating or restoring a wallet
    public void setWallet(Wallet newWallet) throws GeneralSecurityException, IOException {
        EnumSet<AuthenticationKeyChain.KeyChainType> authKeyTypes = EnumSet.of(
                AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER,
                AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING,
                AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR,
                AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR,
                AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY,
                AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_FUNDING,
                AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_TOPUP,
                AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING
        );
        this.wallet = newWallet;
        walletStateFlow.setValue(newWallet);
        // TODO: move to a wallet creation class
        if (!wallet.hasKeyChain(Constants.BIP44_PATH)) {
            wallet.addKeyChain(Constants.BIP44_PATH);
        }

        if (wallet.getKeyChainExtensions().containsKey(AuthenticationGroupExtension.EXTENSION_ID)) {
            authenticationGroupExtension = (AuthenticationGroupExtension) wallet.getKeyChainExtensions().get(AuthenticationGroupExtension.EXTENSION_ID);
            if (authKeyTypes.stream().anyMatch(keyType -> authenticationGroupExtension.getKeyChain(keyType) == null)) {
                // if the wallet is encrypted, don't add these keys
                if (!wallet.isEncrypted()) {
                    authenticationGroupExtension.addKeyChains(
                            wallet.getParams(),
                            wallet.getKeyChainSeed(),
                            authKeyTypes
                    );

                    authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY);
                    authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_FUNDING);
                    authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_TOPUP);
                    authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.INVITATION_FUNDING);

                    authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_OWNER);
                    authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_VOTING);
                    authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_OPERATOR);
                    authenticationGroupExtension.freshKey(AuthenticationKeyChain.KeyChainType.MASTERNODE_PLATFORM_OPERATOR);
                    authenticationGroupExtension.setWallet(wallet);
                }
            }
        }
        WalletEx walletEx = (WalletEx) wallet;
        if (walletEx.getCoinJoin() != null) {
            // Mixing was removed from the app, but wallets that mixed in the past still hold
            // funds on the CoinJoin keychain. Initializing it here keeps those UTXOs
            // recognized and spendable through the regular send flow.
            walletEx.initializeCoinJoin(null, 0);
        }

        // Phase 5d restore/new-wallet cutover: setWallet is called ONLY by
        // onboarding after CREATING or RESTORING a wallet (a normal launch
        // and an app upgrade both load via loadWalletFromProtobuf, not here),
        // so this is exactly the "fresh wallet setup happening now" seam. A
        // freshly created/restored wallet has no already-synced dashj balance
        // to protect, so make the SDK L1-primary from the start (dashj held,
        // SDK does the fast initial sync). Fire-and-forget on the app IO scope:
        // the restore-from-FILE caller invokes setWallet on the MAIN thread, so
        // this must NOT block on DataStore I/O. The commit self-gates on the SDK
        // L1 flag and is a no-op if already committed; the home screen reads
        // cutover state reactively.
        //
        // An UPGRADE install never reaches here (it loads via
        // loadWalletFromProtobuf) — it commits at the finalizeInitialization
        // seam below instead. But the converse is NOT true: a fresh
        // create/restore reaches BOTH seams, because onboarding's PIN step calls
        // saveWalletAndFinalizeInitialization() -> finalizeInitialization()
        // afterwards. That is why this call also latches
        // "freshWalletSetupThisLaunch" inside the coordinator, which suppresses
        // the one-time UPGRADE sync explainer the other seam would otherwise arm.
        cutoverCoordinator.commitForFreshWalletSetupAsync();
    }

    public void saveWalletAndFinalizeInitialization() {
        saveWallet();
        backupWallet();

        config.armBackupReminder();

        finalizeInitialization();
    }

    public void finalizeInitialization() {
        long _t = System.currentTimeMillis();
        StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_FINALIZE_INIT_BEGIN, "FINALIZE_INIT_BEGIN");
        // TODO, put this in a different place. maybe SecurityInitilizer
        // TODO, can we remove this?
        try {
            SecurityGuard securityGuard = SecurityGuard.getInstance();
            List<String> mnemonicWords = platformRepo.getWalletSeed().getMnemonicCode();
            if (mnemonicWords != null) {
                securityGuard.ensureMnemonicFallbacks(mnemonicWords);
                log.info("Mnemonic-based fallbacks ensured");
            }
            boolean success = securityGuard.ensurePinFallback(securityGuard.retrievePin());
            if (success) {
                log.info("PIN-based fallback added successfully");
            }
        } catch (Exception e) {
            log.error("Failed to ensure mnemonic-based fallbacks", e);
            // Don't crash - app can continue with primary+PIN fallback only
        }
        log.info("STARTUP finalizeInit: securityGuard done in {}ms", System.currentTimeMillis() - _t); _t = System.currentTimeMillis();

        dashSystemService.getSystem().initDash(true, true, Constants.SYNC_FLAGS, Constants.VERIFY_FLAGS);
        log.info("STARTUP finalizeInit: initDash done in {}ms", System.currentTimeMillis() - _t); _t = System.currentTimeMillis();
        StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_INIT_DASH_DONE, "INIT_DASH_DONE");

        if (config.versionCodeCrossed((int)packageInfoProvider.getVersionCode(), VERSION_CODE_SHOW_BACKUP_REMINDER)
                && !wallet.getImportedKeys().isEmpty()) {
            log.info("showing backup reminder once, because of imported keys being present");
            config.armBackupReminder();
        }

        config.updateLastVersionCode((int)packageInfoProvider.getVersionCode());

        if (config.getTaxCategoryInstallTime() == 0) {
            config.setTaxCategoryInstallTime(System.currentTimeMillis());
        }

        afterLoadWallet();
        log.info("STARTUP finalizeInit: afterLoadWallet done in {}ms", System.currentTimeMillis() - _t); _t = System.currentTimeMillis();
        StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_AFTER_LOAD_WALLET_DONE, "AFTER_LOAD_WALLET_DONE");

        cleanupFiles();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels();
        }

        if (Constants.SUPPORTS_PLATFORM) {
            // Catch-degrade: initPlatform kicks the SDK bind + L1 engine +
            // cutover services. A failure here must not stop the app from
            // opening — the wallet UI still works from the display cache.
            runStartupStage(StartupBreadcrumbs.STAGE_PLATFORM_INIT_KICKED, "PLATFORM_INIT_KICKED",
                    this::initPlatform);
        }
        log.info("STARTUP finalizeInit: initPlatform+channels done in {}ms", System.currentTimeMillis() - _t); _t = System.currentTimeMillis();
        // Catch-degrade: third-party integration setup must never block the
        // wallet from opening.
        runStartupStage(-1, "INTEGRATIONS", () -> {
            initUphold();
            initCoinbase();
            initDashSpend();
            WalletApplicationExt.INSTANCE.clearCachedAddresses(this);
        });
        log.info("STARTUP finalizeInit: integrations done in {}ms", System.currentTimeMillis() - _t);
    }

    private void initUphold() {
        //Uses Sha256 hash of excerpt of xpub as Uphold authentication salt
        String xpub = wallet.getWatchingKey().serializePubB58(Constants.NETWORK_PARAMETERS);
        byte[] xpubExcerptHash = Sha256Hash.hash(xpub.substring(4, 15).getBytes());
        String authenticationHash = Sha256Hash.wrap(xpubExcerptHash).toString();

        UpholdConstants.CLIENT_ID = BuildConfig.UPHOLD_CLIENT_ID;
        UpholdConstants.CLIENT_SECRET = BuildConfig.UPHOLD_CLIENT_SECRET;
        UpholdConstants.INSTANCE.initialize(Constants.NETWORK_PARAMETERS.getId().contains("test"));
        UpholdClient.init(getApplicationContext(), authenticationHash, upholdConfig);
        LiquidClient.Companion.init(getApplicationContext(), authenticationHash);
    }

    private void initPlatform() {
        platformSyncService.init();
    }

    private void initCoinbase() {
        CoinBaseClientConstants.CLIENT_ID = BuildConfig.COINBASE_CLIENT_ID;
        CoinBaseClientConstants.CLIENT_SECRET = BuildConfig.COINBASE_CLIENT_SECRET;
    }

    private void initDashSpend() {
        // there is nothing to set for now. No client id or client secret.
        // X-Client-Id will be set as "dcg_android"
    }

    @RequiresApi(Build.VERSION_CODES.O)
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

        //DashPay
        createNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_DASHPAY,
                R.string.notification_dashpay_channel_name,
                R.string.notification_dashpay_channel_description,
                NotificationManager.IMPORTANCE_LOW);

        // Incoming contact requests. Separate from the DashPay channel above on purpose: that one
        // is IMPORTANCE_LOW for the identity-creation progress notification, and a channel's
        // importance cannot be changed once the system has created it.
        createNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_CONTACTS,
                R.string.notification_contacts_channel_name,
                R.string.notification_contacts_channel_description,
                NotificationManager.IMPORTANCE_HIGH);
    }

    @RequiresApi(Build.VERSION_CODES.O)
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
        wallet.setSaveOnNextBlock(false);
        wallet.autosaveToFile(walletFile, Constants.Files.WALLET_AUTOSAVE_DELAY_MS, TimeUnit.MILLISECONDS, null);
        final Wallet walletForMaintenance = wallet;

        // did blockchain rescan fail
        if (config.isResetBlockchainPending()) {
            log.info("failed to finish reset earlier, performing now...");
            deleteBlockchainFiles();
            Toast.makeText(this, "finishing blockchain rescan", Toast.LENGTH_LONG).show();
            WalletApplicationExt.INSTANCE.clearDatabases(this, false);
            config.clearResetBlockchainPending();
        }

        // DEFERRED O(wallet) maintenance — off the critical launch path. Both
        // walks scale with wallet size (cleanup iterates transactions under the
        // wallet lock; backupWallet builds the FULL Protos.Wallet tree — a
        // multi-hundred-MB transient on a very large CoinJoin wallet — before
        // stripping transactions). Neither is needed for the UI to come up, and
        // on a huge wallet doing them synchronously inside Application.onCreate
        // risks the exact startup ANR/OOM crash-loop this launch path is being
        // hardened against. Failures degrade (log + non-fatal trace), never crash.
        new Thread(() -> {
            try {
                // clean up spam
                try {
                    walletForMaintenance.cleanup();
                } catch (IllegalStateException x) {
                    // Catch an inconsistent exception here and reset the blockchain.  This is for loading older wallets that had
                    // txes with fees that were too low or dust that were stuck and could not be sent.  In a later version
                    // the fees were fixed, then those stuck transactions became inconsistent and the exception is thrown.
                    if (x.getMessage() != null && x.getMessage().contains("Inconsistent spent tx:")) {
                        deleteBlockchainFiles();
                    } else {
                        throw x;
                    }
                }

                // make sure there is at least one recent backup
                if (!getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF).exists())
                    backupWallet();
            } catch (final Throwable t) {
                log.error("deferred wallet maintenance failed — continuing degraded", t);
                try {
                    CrashReporter.saveBackgroundTrace(t, packageInfoProvider.getPackageInfo());
                } catch (final Throwable ignored) {
                }
            }
        }, "wallet-startup-maintenance").start();

        // setup WalletBalanceObserver
        walletBalanceObserver = new WalletBalanceObserver(wallet, walletUIConfig);

        // Parity-free cutover (QA directive): make the SDK L1-primary from the FIRST
        // launch for EVERY wallet — UPGRADES included, not just fresh create/restore
        // (which commit in setWallet). Each Phase-1 function is tested AFTER cutover, so
        // dashj should never have to dual-run and parity-match before the SDK takes over.
        // Idempotent (no-op once CUT_OVER) and self-gated on USE_KOTLIN_SDK_L1_SHADOW, so
        // it stays inert when the SDK L1 engine is off; once committed the
        // CutoverAutoCommitObserver parity path never runs (it stands down when CUT_OVER).
        //
        // The UPGRADE variant: identical commit, but it also arms the one-time
        // sync explainer when this launch is the one that actually flips the
        // state AND the previous version was pre-11.10 (see
        // CutoverCoordinator.commitForUpgradedWalletAsync).
        //
        // NOTE this seam is NOT upgrade-only. It runs on every launch that
        // loads the wallet from the protobuf (WalletApplication:936) AND at the
        // end of onboarding, because SetPinViewModel.initWallet() calls
        // saveWalletAndFinalizeInitialization() -> finalizeInitialization()
        // right after setWallet() created/restored the wallet. So a fresh
        // create/restore DOES reach here; the coordinator suppresses the
        // explainer for it via the freshWalletSetupThisLaunch latch that
        // setWallet's commit sets synchronously.
        //
        // config.lastVersionCode is the versionCode recorded by the PREVIOUS
        // launch (0 on a never-run install). It is a final field captured when
        // Configuration was constructed, so it still holds the pre-upgrade
        // value here even though finalizeInitialization already persisted this
        // launch's code via config.updateLastVersionCode() — and it is stable
        // no matter which of the two afterLoadWallet() call paths runs first.
        cutoverCoordinator.commitForUpgradedWalletAsync(config.lastVersionCode);
    }

    private void deleteBlockchainFiles() {
        File blockChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME);
        blockChainFile.delete();
        File headerChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.HEADERS_FILENAME);
        headerChainFile.delete();
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

        final LogMarkerFilter markerFilter = new LogMarkerFilter();
        markerFilter.addAcceptedMarker("PUBLISH");
        final PatternLayoutEncoder filePattern = new PatternLayoutEncoder();
        filePattern.setContext(context);
        filePattern.setPattern("%d{HH:mm:ss,UTC} [%thread] %logger{0} - %msg%n");
        filePattern.start();

        final RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<ILoggingEvent>();
        fileAppender.setContext(context);
        fileAppender.setFile(logFile.getAbsolutePath());

        final SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new SizeAndTimeBasedRollingPolicy<ILoggingEvent>();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(fileAppender);
        rollingPolicy.setFileNamePattern(logDir.getAbsolutePath() + "/wallet.%i.%d{yyyy-MM-dd,UTC}.log.gz");
        rollingPolicy.setMaxHistory(20);
        rollingPolicy.setMaxFileSize(FileSize.valueOf("10MB"));
        rollingPolicy.setTotalSizeCap(FileSize.valueOf("200MB"));
        rollingPolicy.start();


        fileAppender.setEncoder(filePattern);
        fileAppender.setRollingPolicy(rollingPolicy);
        fileAppender.addFilter(markerFilter);
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
        logcatAppender.addFilter(markerFilter);
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

    private final MutableStateFlow<Wallet> walletStateFlow = StateFlowKt.MutableStateFlow(null);
    @NonNull
    @Override
    public
    Flow<Wallet> observeWallet() {
        return walletStateFlow;
    }

    @Nullable
    @Override
    public AuthenticationGroupExtension getAuthenticationGroupExtension() {
        return authenticationGroupExtension;
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
        // PRE-PARSE SIZE GUARD (empirically grounded — see WalletFileSizeGuard):
        // the parse peaks at ~8x the file size in heap, and a >=2GB file is
        // unparseable at ANY heap size (protobuf's 2GiB CodedInputStream wall;
        // the WRITE side streams past it silently). Decide BEFORE touching the
        // parser whether this file can possibly load.
        final long fileSize = walletFile.length();
        final int largeMemoryClassMb = largeMemoryClassMb();
        final WalletFileSizeGuard.Verdict sizeVerdict = WalletFileSizeGuard.verdict(fileSize, largeMemoryClassMb);
        if (sizeVerdict != WalletFileSizeGuard.Verdict.NORMAL) {
            log.warn("wallet file size guard: {} bytes, largeHeap {}MB, soft limit {} bytes -> {}",
                    fileSize, largeMemoryClassMb, WalletFileSizeGuard.softLimitBytes(largeMemoryClassMb), sizeVerdict);
        }

        if (sizeVerdict == WalletFileSizeGuard.Verdict.UNPARSEABLE) {
            // The file is beyond the point of no return by construction — do
            // NOT attempt the parse (the attempt OOM-crash-loops the launch on
            // any real device and can never succeed). Preserve the file
            // untouched under a timestamped name (forensics + safety, never
            // delete) and go straight to the deliberate key-backup recovery.
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_FILE_OVERSIZE, "WALLET_FILE_OVERSIZE",
                    "size=" + fileSize + " hardLimit=" + WalletFileSizeGuard.HARD_LIMIT_BYTES);
            final File preserved = WalletFileSizeGuard.preserveAside(walletFile, "oversize");
            log.error("wallet file is {} bytes (>= {} hard limit) — unparseable by construction; "
                    + "preserved as '{}', recovering from the key backup", fileSize,
                    WalletFileSizeGuard.HARD_LIMIT_BYTES, preserved != null ? preserved.getName() : "(rename failed)");
            wallet = restoreWalletFromBackup();
            adoptAuthenticationGroupExtension();
        } else {
            FileInputStream walletStream = null;
            boolean parsed = false;
            // TIME guard, the companion to the size guard above. The DashPay
            // friend-key-chain crash loop was a 2.5MB wallet that took MINUTES
            // to parse: the parse cannot be interrupted safely, so the watchdog
            // records the over-budget launch and arms the crash-loop breaker so
            // the NEXT launch opens in safe mode if this one dies.
            final WalletLoadBudget.Watchdog budget = WalletLoadBudget.arm(WalletLoadBudget.DEFAULT_BUDGET_MS, () -> {
                log.error("wallet load exceeded its {}ms budget ({} DashPay friend chains still deriving) — "
                                + "arming safe mode for the next launch",
                        WalletLoadBudget.DEFAULT_BUDGET_MS, FriendKeyChainLookahead.pendingCount());
                StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_OVERBUDGET, "WALLET_LOAD_OVERBUDGET",
                        "budgetMs=" + WalletLoadBudget.DEFAULT_BUDGET_MS
                                + " pendingFriendChains=" + FriendKeyChainLookahead.pendingCount());
                StartupBreadcrumbs.armSafeModeOnNextDeath();
            });
            try {
                final Stopwatch watch = Stopwatch.createStarted();
                walletStream = new FileInputStream(walletFile);
                // Keep the DashPay friend-key-chain lookahead OFF the parse:
                // 100+33 keys per CONTACT chain is ~1.2s each on a real device
                // and blocks Application.onCreate for minutes on a wallet with
                // many contacts. The identical derivations run on a background
                // pool right after this, and the blockchain service waits for
                // them before the wallet is attached to the peer group — so the
                // watched-key set is unchanged. See FriendKeyChainLookahead.
                FriendKeyChainLookahead.reset();
                final WalletProtobufSerializer serializer = new WalletProtobufSerializer();
                serializer.setKeyChainFactory(FriendKeyChainLookahead.deferringFactory());
                wallet = serializer.readWallet(walletStream, false, walletFactory.getExtensions(Constants.NETWORK_PARAMETERS));

                adoptAuthenticationGroupExtension();
                if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS))
                    throw new UnreadableWalletException("bad wallet network parameters: " + wallet.getParams().getId());

                log.info("wallet loaded from: '{}', took {} ({} DashPay friend chains deferred)", walletFile, watch,
                        FriendKeyChainLookahead.deferredCount());
                StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_PROTOBUF_PARSED,
                        "WALLET_PROTOBUF_PARSED", "took=" + watch
                                + " deferredFriendChains=" + FriendKeyChainLookahead.deferredCount());
                // Overlap the deferred derivations with the rest of startup.
                parsed = true;
                FriendKeyChainLookahead.completeAsync();
            } catch (final FileNotFoundException x) {
                log.error("problem loading wallet", x);

                Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

                wallet = restoreWalletFromBackup();
                adoptAuthenticationGroupExtension();
            } catch (final UnreadableWalletException x) {
                log.error("problem loading wallet", x);

                Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

                wallet = restoreWalletFromBackup();
                adoptAuthenticationGroupExtension();
            } catch (final OutOfMemoryError oom) {
                if (sizeVerdict == WalletFileSizeGuard.Verdict.RISKY) {
                    // EXPECTED failure mode of a risky-size file (>= min(heap/10,
                    // 100MB) — the measured 8x parse multiplier leaves no
                    // headroom): route to the same deliberate recovery as the
                    // hard guard instead of crash-looping. Preserve the file
                    // aside first so the next launch cannot re-trip the OOM.
                    log.error("OOM parsing a RISKY-size wallet file ({} bytes, largeHeap {}MB) — "
                            + "preserving the file and recovering from the key backup", fileSize, largeMemoryClassMb);
                    StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_PARSE_OOM_RECOVERED,
                            "WALLET_PARSE_OOM_RECOVERED", "size=" + fileSize);
                    try {
                        CrashReporter.saveBackgroundTrace(oom, packageInfoProvider.getPackageInfo());
                    } catch (final Throwable ignored) {
                    }
                    WalletFileSizeGuard.preserveAside(walletFile, "oomed");
                    wallet = restoreWalletFromBackup();
                    adoptAuthenticationGroupExtension();
                } else {
                    // A NORMAL-size file OOMing is anomalous (not provably the
                    // file's fault) — do NOT wipe anything; let the onCreate
                    // catch-degrade open the app for crash reporting with the
                    // wallet file untouched.
                    throw oom;
                }
            } finally {
                final long loadMs = budget.disarm();
                if (WalletLoadBudget.isOverBudget(loadMs, WalletLoadBudget.DEFAULT_BUDGET_MS)) {
                    log.warn("wallet load finished OVER budget: {}ms (budget {}ms)",
                            loadMs, WalletLoadBudget.DEFAULT_BUDGET_MS);
                }
                if (!parsed) {
                    // The parse was abandoned (recovery path took over): its
                    // deferred chains belong to a wallet nobody holds, so drop
                    // them instead of deriving keys for an orphan object graph.
                    FriendKeyChainLookahead.reset();
                }
                if (walletStream != null) {
                    try {
                        walletStream.close();
                    } catch (final IOException x) {
                        // swallow
                    }
                }
            }
        }

        if (wallet != null) {
            wallet.setRiskAnalyzer(new AllowLockTimeRiskAnalysis.OfflineAnalyzer(config.getBestHeightEver(), System.currentTimeMillis()/1000));

            if (!isWalletConsistent(wallet)) {
                Toast.makeText(WalletApplication.this, "inconsistent wallet: " + walletFile, Toast.LENGTH_LONG).show();

                wallet = restoreWalletFromBackup();
                adoptAuthenticationGroupExtension();
            }
        }

        if (wallet == null) {
            // Every recovery avenue is exhausted (primary unusable AND the key
            // backup missing/unreadable — restoreWalletFromBackup() already set
            // walletRecoveryFromSeedNeeded). Open DEGRADED instead of throwing:
            // OnboardingActivity shows the "restore from your recovery phrase"
            // state plus the crash report. NOTHING is wiped or overwritten.
            walletLoadFailed = true;
            log.error("wallet load AND key-backup recovery both failed — opening degraded "
                    + "(restore from seed required)");
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_LOAD_FAILED, "WALLET_LOAD_FAILED",
                    "recovery exhausted; restore from seed required");
            return;
        }

        if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS))
            throw new Error("bad wallet network parameters: " + wallet.getParams().getId());
        StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_CONSISTENCY_CHECKED, "WALLET_CONSISTENCY_CHECKED");
        walletStateFlow.setValue(wallet);
        finalizeInitialization();
    }

    /** The device's largeHeap limit in MB (the manifest sets largeHeap="true"), conservative fallback. */
    private int largeMemoryClassMb() {
        try {
            final ActivityManager am = activityManager != null ? activityManager
                    : (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                return am.getLargeMemoryClass();
            }
        } catch (final Throwable t) {
            log.warn("failed to read largeMemoryClass", t);
        }
        // Conservative: a small assumed heap only lowers the RISKY threshold,
        // which still ATTEMPTS the parse — it only changes OOM routing.
        return 256;
    }

    /** Adopt the (re)loaded wallet's AuthenticationGroupExtension; no-op when no wallet. */
    private void adoptAuthenticationGroupExtension() {
        if (wallet == null) {
            return;
        }
        final WalletExtension extension = wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID);
        if (extension != null) {
            this.authenticationGroupExtension = (AuthenticationGroupExtension) extension;
        }
    }

    /**
     * Consistency check that is SAFE on a very large wallet.
     *
     * <p>dashj's {@link Wallet#isConsistent()} swallows the {@link IllegalStateException} from
     * {@code isConsistentOrThrow()} and then logs {@code this.toString()} — a full textual dump of
     * EVERY transaction and EVERY key in the wallet, built as a single String. On a large mainnet
     * CoinJoin wallet (100k+ mixing transactions) that dump is a multi-hundred-megabyte allocation
     * on top of an already fully-inflated wallet, and it is emitted on the main thread from inside
     * {@code Application.onCreate}. The result is process death (heap exhaustion, or an LMK reap
     * once RSS balloons) before the app can reach its own recovery path — and because the wallet
     * file is unchanged, it repeats on every launch: a crash-loop that only a reinstall escapes.
     *
     * <p>Calling {@code isConsistentOrThrow()} directly gives the identical verdict and keeps the
     * diagnostic (the exception message names the offending transaction) without ever building the
     * dump. Returns false exactly where {@code isConsistent()} would have.
     */
    private boolean isWalletConsistent(final Wallet walletToCheck) {
        try {
            walletToCheck.isConsistentOrThrow();
            return true;
        } catch (final IllegalStateException x) {
            // Deliberately NOT logging walletToCheck.toString() — see the javadoc above.
            log.error("inconsistent wallet: {}", x.getMessage());
            return false;
        }
    }

    /**
     * Restore the transaction-stripped KEY backup ({@code key-backup-protobuf})
     * — the deliberate recovery for an unusable primary wallet file. Returns
     * {@code null} (and latches {@link #walletRecoveryFromSeedNeeded}) when the
     * backup itself is missing, unreadable or inconsistent: this method must
     * NEVER throw out of {@code Application.onCreate} — the old
     * {@code Error("cannot read backup")} was itself a guaranteed crash loop.
     * The caller degrades into the safe-mode/report path instead.
     */
    @Nullable
    private Wallet restoreWalletFromBackup() {
        InputStream is = null;

        try {
            is = openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);
            final Wallet wallet = new WalletProtobufSerializer().readWallet(is, true, walletFactory.getExtensions(Constants.NETWORK_PARAMETERS));

            if (!isWalletConsistent(wallet))
                throw new UnreadableWalletException("inconsistent backup");

            wallet.addKeyChain(Constants.BIP44_PATH);

            resetBlockchain();

            Toast.makeText(this, R.string.toast_wallet_reset, Toast.LENGTH_LONG).show();

            log.info("wallet restored from backup: '{}'", Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_RECOVERED_FROM_BACKUP,
                    "WALLET_RECOVERED_FROM_BACKUP");

            // POST-RECOVERY GUARD: if the Tools "dashj sync (diagnostic)"
            // toggle is ON, force it OFF. With the toggle on, the un-held dashj
            // peergroup dirties the wallet continuously and the 5s autosave
            // rewrites an ever-growing file — the exact growth engine that can
            // balloon a wallet past the 2GB point of no return. The freshly
            // recovered small wallet must not start re-ballooning on its first
            // session. One-line user notice via Toast when it was actually on.
            // TODO(autosave-cap PR): the real fix is a size-aware autosave
            // policy (prune/cap, longer delay above a size threshold) — a
            // separate PR; this recovery-time toggle-off is the stopgap.
            WalletApplicationExt.INSTANCE.disableDashjSyncDiagnosticAfterRecovery(this);

            return wallet;
        } catch (final IOException x) {
            log.error("cannot read backup — wallet needs a restore from seed", x);
            walletRecoveryFromSeedNeeded = true;
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_BACKUP_UNUSABLE,
                    "WALLET_BACKUP_UNUSABLE", x.getClass().getName() + ": " + x.getMessage());
            try {
                CrashReporter.saveBackgroundTrace(x, packageInfoProvider.getPackageInfo());
            } catch (final Throwable ignored) {
            }
            return null;
        } catch (final UnreadableWalletException x) {
            log.error("cannot read backup — wallet needs a restore from seed", x);
            walletRecoveryFromSeedNeeded = true;
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_WALLET_BACKUP_UNUSABLE,
                    "WALLET_BACKUP_UNUSABLE", x.getClass().getName() + ": " + x.getMessage());
            try {
                CrashReporter.saveBackgroundTrace(x, packageInfoProvider.getPackageInfo());
            } catch (final Throwable ignored) {
            }
            return null;
        } finally {
            // `is` is still null when openFileInput() itself threw (no backup file at all).
            // Without this guard the finally block raises a NullPointerException that REPLACES
            // the real "cannot read backup" failure, destroying the only diagnostic we have.
            if (is != null) {
                try {
                    is.close();
                } catch (final IOException x) {
                    // swallow
                }
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

        // Write atomically (temp -> fsync -> rename). This backup is the ONLY fallback
        // loadWalletFromProtobuf() has when the primary wallet fails to parse, and dashj keeps no
        // backup of its own. Writing it in place (the previous behaviour) meant a kill mid-write
        // left a TRUNCATED backup, so a later primary-wallet failure would hit
        // restoreWalletFromBackup() -> Error("cannot read backup") thrown straight out of
        // Application.onCreate — an unrecoverable crash-loop with both copies unusable.
        // dashj already writes the primary wallet this way (Wallet.saveToFile temp+rename).
        try {
            AtomicFileWriter.write(this, Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, walletProto::writeTo);
            watch.stop();
            log.info("wallet backed up to: '{}', took {}", Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, watch);
        } catch (final IOException x) {
            log.error("problem writing wallet backup", x);
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
        // Clear live DataStore-backed configs through their API first: deleting the
        // backing file of a LIVE DataStore out-of-band leaves its in-memory cache
        // populated while disk is empty (memory/disk desync — observed live as the
        // debug SDK flags never reseeding after a Reset Wallet and datastore files
        // recreated with a random subset of keys). The API-level clear resets
        // memory and disk atomically.
        final Set<String> apiCleared = WalletApplicationExt.INSTANCE.clearLiveConfigs();

        // File-delete only the datastore files with no live DataStore instance
        // (configs never instantiated this process have no in-memory cache, so raw
        // deletion is safe for them). Deleting an api-cleared file here would
        // desynchronize its live cache again.
        final List<String> fileDeleted = new ArrayList<>();
        final File folder = new File(getFilesDir(), Constants.Files.DATASTORE_PREFS_DIRECTORY);

        if (folder.isDirectory()) {
            final File[] files = folder.listFiles();

            if (files != null) {
                for (File file : files) {
                    if (!apiCleared.contains(file.getName())) {
                        if (file.delete()) {
                            fileDeleted.add(file.getName());
                        } else {
                            log.warn("failed to delete datastore preferences file: '{}'", file.getName());
                        }
                    }
                }
            }
        }

        log.info("datastore preferences cleared; api-cleared: {}, file-deleted: {}", apiCleared, fileDeleted);
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
            if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                if (wallet == null) {
                    log.warn("wallet does not exist, but starting blockchain service");
                }
                if (cancelCoinsReceived) {
                    Intent blockchainServiceCancelCoinsReceivedIntent = new Intent(BlockchainService.ACTION_CANCEL_COINS_RECEIVED, null,
                            this, BlockchainServiceImpl.class);
                    startService(blockchainServiceCancelCoinsReceivedIntent);
                } else {
                    startService(blockchainServiceIntent);
                }
            }
        }
    }

    @Deprecated(message = "not used")
    public void stopBlockchainService() {
        stopService(blockchainServiceIntent);
    }

    /**
     * DIAGNOSTIC (Tools "dashj sync" toggle): bounce the blockchain service so
     * it runs a fresh onCreate and re-resolves the Phase 5d engine-start gate
     * ({@code dashjEngineMayStart}) against the new
     * {@code DASHJ_SYNC_DIAGNOSTIC} value — starting the dashj peergroup when
     * the diagnostic is turned on, or re-holding it when turned off. Stops then
     * (after a short delay for the teardown to settle) starts the service.
     */
    public void restartBlockchainService() {
        stopService(blockchainServiceIntent);
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> startBlockchainService(false), 1500);
    }

    public void resetBlockchainState() {
        blockchainStateDataProvider.resetBlockchainState();
    }

    public void resetBlockchain() {
        // reset the extensions
        if (wallet != null && authenticationGroupExtension != null) {
            authenticationGroupExtension.reset();
        }
        // Clear the in-memory pre-built rows immediately so that the new Activity
        // launched by the caller does not display stale cached transaction data.
        txDisplayCacheService.clearInMemoryCache();
        // implicitly stops blockchain service
        resetBlockchainState();
        Intent blockchainServiceResetBlockchainIntent = new Intent(BlockchainService.ACTION_RESET_BLOCKCHAIN, null, this,
                BlockchainServiceImpl.class);
        startService(blockchainServiceResetBlockchainIntent);
    }

    private void resetBlockchainSyncProgress() {
        blockchainStateDataProvider.resetBlockchainSyncProgress();
    }

    @Deprecated(message = "not used")
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
    public void processDirectTransaction(@NonNull final Transaction tx) throws VerificationException {
        if (wallet.isTransactionRelevant(tx)) {
            wallet.receivePending(tx, null);
            broadcastTransaction(tx);
        }
    }

    public void broadcastTransaction(final Transaction tx) {
        final Intent intent = new Intent(BlockchainService.ACTION_BROADCAST_TRANSACTION, null, this,
                BlockchainServiceImpl.class);
        intent.putExtra(BlockchainService.ACTION_BROADCAST_TRANSACTION_HASH, tx.getTxId().getBytes());
        startService(intent);
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

    @SuppressLint({"NewApi", "WrongConstant"})
    public static void scheduleStartBlockchainService(final Context context, Boolean cancelOnly) {
        final Configuration config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context));
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
    public void triggerWipe(Function0 afterWipeFunction) {
        log.info("Removing all the data and restarting the app.");
        this.afterWipeFunction = afterWipeFunction;
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
        clearDatastorePrefs();
        clearWebCookies();
        notifyWalletWipe();
        PinRetryController.getInstance().clearPinFailPrefs();
        MnemonicCodeExt.clearWordlistPath(this);
        // TODO: get rid of a separate file for this pref
        getSharedPreferences(HistoryHeaderAdapter.PREFS_FILE_NAME, MODE_PRIVATE).edit().clear().apply();
        WorkManager.getInstance(this).pruneWork();
        try {
            SecurityGuard.getInstance().removeKeys();
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            log.warn("error occurred when removing security keys", e);
        }

        File walletBackupFile = getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);
        if (walletBackupFile.exists()) {
            walletBackupFile.delete();
        }

        // clear data on wallet reset
        WalletApplicationExt.INSTANCE.clearDatabases(this, true);
        // wallet must be null for the OnboardingActivity flow
        log.info("removing wallet from memory during wipe");
        wallet = null;
        walletStateFlow.setValue(null);
        authenticationGroupExtension = null;
        walletBalanceObserver.close();
        walletBalanceObserver = null;
        if (afterWipeFunction != null)
            afterWipeFunction.invoke();
        afterWipeFunction = null;
    }

    public AnalyticsService getAnalyticsService() {
        return analyticsService;
    }

    private void notifyWalletWipe() {
        // Since these are now suspended listeners, we need to call them in a blocking way
        // to ensure all clearing operations complete before proceeding
        for (Function1<? super Continuation<? super Unit>, ?> listener : wipeListeners) {
            try {
                // Call the suspended function synchronously using runBlocking
                kotlinx.coroutines.BuildersKt.runBlocking(
                    kotlinx.coroutines.Dispatchers.getIO(),
                    (scope, continuation) -> listener.invoke(continuation)
                );
            } catch (Exception e) {
                log.error("Error in wallet wipe listener", e);
            }
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
        if (!autoLogout.isTimerActive()) {
            autoLogout.startTimer();
        }
    }

    @Override
    public void stopAutoLogoutTimer() {
        autoLogout.stopTimer();
    }

    @NotNull
    @Override
    public Address currentReceiveAddress() {
        return wallet.currentReceiveAddress();
    }

    @NotNull
    @Override
    public Address freshReceiveAddress() {
        return wallet.freshReceiveAddress();
    }

    @NotNull
    @Override
    public String getNetworkId() {
        return getNetworkParameters().getId();
    }

    @NotNull
    @Override
    public String currentReceiveAddressString() {
        return currentReceiveAddress().toBase58();
    }

    @NotNull
    @Override
    public String freshReceiveAddressString() {
        return freshReceiveAddress().toBase58();
    }

    @NotNull
    public Coin getWalletBalance() {
        // Phase 5d: post-cutover the dashj wallet is held/frozen, so the SDK
        // balance (non-null only after a committed cutover) wins.
        final Coin sdkBalance = cutoverUiDataService != null ? cutoverUiDataService.sdkBalanceOrNull() : null;
        if (sdkBalance != null) {
            return sdkBalance;
        }
        if (wallet == null || walletBalanceObserver == null) {
            return Coin.ZERO;
        }
        if (!walletBalanceObserver.isSeeded()) {
            // Cold start: the observer's async DataStore seed hasn't landed yet and its
            // StateFlow still holds the Coin.ZERO construction value — a widget render
            // through this redirect would show zero. Read the wallet directly instead
            // (pre-cutover only; post-cutover the SDK overlay above already served).
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT);
            return wallet.getBalance(Wallet.BalanceType.ESTIMATED);
        }

        // Accepted residual staleness: the observer refreshes through a ~500ms-throttled
        // wallet-change listener, so this cached value can trail a just-landed tx by up
        // to the throttle window; the event-driven widget pushes re-render right after,
        // so it self-corrects.
        return  walletBalanceObserver.getTotalBalance().getValue();
    }

    @Override
    public int spendableUtxoCount() {
        // Post-cutover the dashj wallet is HELD, so its UTXO set is frozen at
        // the cutover snapshot (or empty on a fresh restore) — and unlike the
        // balance this count was never overlaid. Its consumer is the shielded
        // max-fee reserve, which sizes itself at ~148 bytes per input, so a
        // stale count under-reserves (the max-shield retry fails again) or
        // over-reserves (the user cannot shield their full balance). Serve the
        // SDK's live count instead. Null = keep dashj: pre-cutover always, and
        // post-cutover until the SDK scan has caught up — see
        // CutoverUiDataService.sdkSpendableUtxoCountOrNull for why this one
        // deliberately does NOT hold a last-known value the way the balance does.
        final Integer sdkCount = cutoverUiDataService != null
                ? cutoverUiDataService.sdkSpendableUtxoCountOrNull()
                : null;
        if (sdkCount != null) {
            return sdkCount;
        }

        final Wallet wallet = this.wallet;
        if (wallet == null) {
            return 0;
        }
        // the exact output set getBalance(ESTIMATED) sums — see the interface doc
        return wallet.calculateAllSpendCandidates(false, false).size();
    }

    @NonNull
    @Override
    public Flow<Coin> observeTotalBalance() {
        if (wallet == null || walletBalanceObserver == null) {
            return FlowKt.emptyFlow();
        }

        // Phase 5d: cutover-aware. Pre-cutover the overlay's SDK side is
        // permanently null, so this is the dashj feed unchanged; after a
        // committed cutover the SDK's live L1 balance wins (the dashj
        // wallet is held and its balance freezes).
        if (cutoverUiDataService != null) {
            return cutoverUiDataService.overlayTotalBalance(walletBalanceObserver.getTotalBalance());
        }
        return walletBalanceObserver.getTotalBalance();
    }

    @NonNull
    @Override
    public Flow<Coin> observeBalance(
        @NonNull Wallet.BalanceType balanceType,
        @Nullable CoinSelector coinSelector
    ) {
        if (wallet == null || walletBalanceObserver == null) {
            return FlowKt.emptyFlow();
        }

        // Phase 5d/B7: cutover-aware for the selector-less ESTIMATED and
        // ESTIMATED_SPENDABLE streams (what the neutral facade's
        // observeEstimatedBalance() serves, and what the Create-Username funding
        // gate reads via observeBalance(ESTIMATED_SPENDABLE)): post-cutover dashj's
        // held wallet has no coins, so both freeze at 0, and the SDK's live total
        // (which sums the same unspent-output set — the correct spendable figure
        // post-cutover) wins via the same overlay observeTotalBalance() uses —
        // pre-cutover the SDK side is permanently null and dashj values pass through
        // unchanged. Selector-based streams stay dashj-fed: they serve send-path
        // coin selection, which another track owns.
        if (cutoverUiDataService != null
                && (balanceType == Wallet.BalanceType.ESTIMATED
                        || balanceType == Wallet.BalanceType.ESTIMATED_SPENDABLE)
                && coinSelector == null) {
            return cutoverUiDataService.overlayTotalBalance(
                    walletBalanceObserver.observe(balanceType, null));
        }

        return walletBalanceObserver.observe(balanceType, coinSelector);
    }

    @NonNull
    @Override
    public Flow<Coin> observeMaxOutputBalance() {
        if (wallet == null || walletBalanceObserver == null) {
            return FlowKt.emptyFlow();
        }

        // Phase 5d: the send screen's "max sendable" DISPLAY feed. The dashj
        // max-output-coin-selector balance (ESTIMATED total minus the fee to spend
        // it all) is a selector-based stream, so the plain observeBalance() overlay
        // deliberately skips it. Post-cutover the held dashj wallet has no coins, so
        // this freezes at 0; the SDK's ACCOUNT-AWARE max-sendable figure (BIP44
        // spendable + DashPay receival confirmed net of per-sweep fee headroom —
        // what the send-all's sweep-then-drain actually delivers) wins via
        // overlayMaxSendableBalance, which itself falls back to the SDK's live
        // total when the account-level snapshot is unavailable — pre-cutover both
        // SDK sides are permanently null and the dashj max-output value passes
        // through unchanged. This feeds only the DISPLAYED available balance /
        // max-amount cap (and the send-all detection keyed off it); the real
        // send's coin selection is owned independently by SendCoinsTaskRunner
        // and is untouched.
        final Flow<Coin> maxOutput =
                walletBalanceObserver.observe(Wallet.BalanceType.ESTIMATED, new MaxOutputAmountCoinSelector());
        if (cutoverUiDataService != null) {
            return cutoverUiDataService.overlayMaxSendableBalance(maxOutput);
        }
        return maxOutput;
    }


    @NonNull
    @Override
    public Flow<Transaction> observeTransactions(
        boolean withConfidence,
        @NonNull WalletTransactionFilter... filters
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
    public Flow<Unit> observeWalletReset() {
        if (wallet == null) {
            return FlowKt.emptyFlow();
        }

        return new WalletObserver(wallet).observeWalletReset();
    }

    @NonNull
    @Override
    public Flow<List<AuthenticationKeyUsage>> observeAuthenticationKeyUsage() {
        if (wallet == null || authenticationGroupExtension == null) {
            return FlowKt.emptyFlow();
        }
        return new MasternodeObserver(authenticationGroupExtension).observeAuthenticationKeyUsage();
    }

    @Nullable
    @Override
    public Transaction getTransaction(@NonNull Sha256Hash hash) {
        if (wallet == null) {
            return null;
        }

        return wallet.getTransaction(hash);
    }

    @NonNull
    @Override
    public Collection<Transaction> getTransactions(@NonNull WalletTransactionFilter... filters) {
        if (wallet == null) {
            return Lists.newArrayList();
        }
        Set<Transaction> transactions = wallet.getTransactions(true);

        if (filters.length == 0) {
            return transactions;
        }

        ArrayList<Transaction> filteredTransactions = new ArrayList<>();

        for (Transaction tx : transactions) {
            for (WalletTransactionFilter filter : filters) {
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
    public Collection<TransactionWrapper> wrapAllTransactions(@NonNull TransactionWrapperFactory... wrapperFactories) {
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT);
        return TransactionWrapperHelper.INSTANCE.wrapTransactions(
                wallet.getTransactions(true),
                wallet,
                Constants.NETWORK_PARAMETERS,
                wrapperFactories
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
    public void attachOnWalletWipedListener(@NonNull Function1<? super Continuation<? super Unit>,?> listener) {
        wipeListeners.add(listener);
    }

    @Override
    public void detachOnWalletWipedListener(@NonNull Function1<? super Continuation<? super Unit>,?> listener) {
        wipeListeners.remove(listener);
    }

    @Override
    public void checkSendingConditions(
            @Nullable Address address,
            @NonNull Coin amount
    ) throws LeftoverBalanceException {
        BalanceConditionBridge.check(
                getWalletBalance(),
                address,
                amount,
                crowdNodeConfig
        );
    }

    @Override
    public boolean canAffordIdentityCreation() {
        return !getWalletBalance().isLessThan(Constants.DASH_PAY_FEE);
    }

    @Override
    public boolean lockOutput(@NotNull TransactionOutPoint outPoint) {
        if (wallet != null) {
            wallet.lockOutput(outPoint);
            return true;
        }

        return false;
    }
}
