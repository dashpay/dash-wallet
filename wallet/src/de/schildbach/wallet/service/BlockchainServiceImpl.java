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

package de.schildbach.wallet.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.text.format.DateUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.common.base.Stopwatch;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.core.listeners.PeerConnectedEventListener;
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener;
import org.bitcoinj.core.listeners.PreBlocksDownloadListener;
import org.bitcoinj.evolution.AssetLockTransaction;
import org.bitcoinj.evolution.SimplifiedMasternodeList;
import org.bitcoinj.evolution.SimplifiedMasternodeListDiff;
import org.bitcoinj.evolution.SimplifiedMasternodeListManager;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.MasternodePeerDiscovery;
import org.bitcoinj.net.discovery.MultiplexingDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.net.discovery.PeerDiscoveryException;
import org.bitcoinj.net.discovery.SeedPeers;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.DefaultRiskAnalysis;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletEx;
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.data.WalletUIConfig;
import org.dash.wallet.common.data.NetworkStatus;
import org.dash.wallet.common.services.TransactionMetadataProvider;
import org.dash.wallet.common.services.NotificationService;
import org.dash.wallet.common.transactions.filters.NotFromAddressTxFilter;
import org.dash.wallet.common.transactions.filters.TransactionFilter;
import org.dash.wallet.common.transactions.TransactionUtils;
import org.dash.wallet.common.util.FlowExtKt;
import org.dash.wallet.common.util.MonetaryExtKt;
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeAPIConfirmationHandler;
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeBlockchainApi;
import org.dash.wallet.integrations.crowdnode.transactions.CrowdNodeDepositReceivedResponse;
import org.dash.wallet.integrations.crowdnode.transactions.CrowdNodeWithdrawalReceivedTx;
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig;
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;
import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletApplicationExt;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.AddressBookProvider;
import org.dash.wallet.common.data.entity.BlockchainState;
import de.schildbach.wallet.database.dao.BlockchainStateDao;
import de.schildbach.wallet.database.dao.ExchangeRatesDao;
import de.schildbach.wallet.service.platform.PlatformSyncService;
import de.schildbach.wallet.ui.OnboardingActivity;
import de.schildbach.wallet.ui.dashpay.OnPreBlockProgressListener;
import de.schildbach.wallet.ui.dashpay.PlatformRepo;
import de.schildbach.wallet.ui.dashpay.PreBlockStage;
import de.schildbach.wallet.ui.staking.StakingActivity;
import de.schildbach.wallet.util.AllowLockTimeRiskAnalysis;
import de.schildbach.wallet.util.BlockchainStateUtils;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet_test.R;

import static org.dash.wallet.common.util.Constants.PREFIX_ALMOST_EQUAL_TO;

/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
public class BlockchainServiceImpl extends LifecycleService implements BlockchainService {

    @Inject WalletApplication application;
    @Inject Configuration config;
    @Inject WalletUIConfig walletUIConfig;
    @Inject NotificationService notificationService;
    @Inject CrowdNodeBlockchainApi crowdNodeBlockchainApi;
    @Inject CrowdNodeConfig crowdNodeConfig;
    @Inject BlockchainStateDao blockchainStateDao;
    @Inject ExchangeRatesDao exchangeRatesDao;
    @Inject TransactionMetadataProvider transactionMetadataProvider;
    @Inject PlatformSyncService platformSyncService;
    @Inject PlatformRepo platformRepo;
    @Inject PackageInfoProvider packageInfoProvider;
    @Inject ConnectivityManager connectivityManager;
    @Inject BlockchainStateDataProvider blockchainStateDataProvider;
    @Inject CoinJoinService coinJoinService; // not used in this class, but we need to create it
    private BlockStore blockStore;
    private BlockStore headerStore;
    private File blockChainFile;
    private File headerChainFile;
    private BlockChain blockChain;
    private BlockChain headerChain;
    private InputStream mnlistinfoBootStrapStream;
    private InputStream qrinfoBootStrapStream;
    @Nullable
    private PeerGroup peerGroup;

    private final Handler handler = new Handler();
    private final Handler delayHandler = new Handler();
    private final Handler metadataHandler = new Handler();
    private WakeLock wakeLock;

    private PeerConnectivityListener peerConnectivityListener;
    private NotificationManager nm;
    private final Set<BlockchainState.Impediment> impediments = EnumSet.noneOf(BlockchainState.Impediment.class);
    private BlockchainState blockchainState = new BlockchainState(null, 0, false, impediments, 0, 0, 0);
    private int notificationCount = 0;
    private Coin notificationAccumulatedAmount = Coin.ZERO;
    private final List<Address> notificationAddresses = new LinkedList<Address>();
    private AtomicInteger transactionsReceived = new AtomicInteger();
    private AtomicInteger mnListDiffsReceived = new AtomicInteger();
    private long serviceCreatedAt;
    private boolean resetBlockchainOnShutdown = false;
    private boolean deleteWalletFileOnShutdown = false;

    //Settings to bypass dashj default dns seeds
    private final SeedPeers seedPeerDiscovery = new SeedPeers(Constants.NETWORK_PARAMETERS);
    private final DnsDiscovery dnsDiscovery = new DnsDiscovery(Constants.DNS_SEED, Constants.NETWORK_PARAMETERS);
    ArrayList<PeerDiscovery> peerDiscoveryList = new ArrayList<>(2);
    private final static int MINIMUM_PEER_COUNT = 16;

    private static final int MIN_COLLECT_HISTORY = 2;
    private static final int IDLE_HEADER_TIMEOUT_MIN = 2;
    private static final int IDLE_MNLIST_TIMEOUT_MIN = 2;
    private static final int IDLE_BLOCK_TIMEOUT_MIN = 2;
    private static final int IDLE_TRANSACTION_TIMEOUT_MIN = 9;
    private static final int MAX_HISTORY_SIZE = Math.max(IDLE_TRANSACTION_TIMEOUT_MIN, IDLE_BLOCK_TIMEOUT_MIN);
    private static final long APPWIDGET_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;
    private static final long BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;
    private static final long TX_EXCHANGE_RATE_TIME_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(180);

    private static final Logger log = LoggerFactory.getLogger(BlockchainServiceImpl.class);

    public static final String START_AS_FOREGROUND_EXTRA = "start_as_foreground";

    private Executor executor = Executors.newSingleThreadExecutor();
    private int syncPercentage = 0; // 0 to 100%
    private MixingStatus mixingStatus = MixingStatus.NOT_STARTED;
    private Double mixingProgress = 0.0;
    private ForegroundService foregroundService = ForegroundService.NONE;

    // Risk Analyser for Transactions that is PeerGroup Aware
    AllowLockTimeRiskAnalysis.Analyzer riskAnalyzer;
    DefaultRiskAnalysis.Analyzer defaultRiskAnalyzer = DefaultRiskAnalysis.FACTORY;

    private final List<TransactionFilter> crowdnodeFilters = Arrays.asList(
            new NotFromAddressTxFilter(CrowdNodeConstants.INSTANCE.getCrowdNodeAddress(Constants.NETWORK_PARAMETERS)),
            new CrowdNodeWithdrawalReceivedTx(Constants.NETWORK_PARAMETERS)
    );

    private final CrowdNodeDepositReceivedResponse depositReceivedResponse =
            new CrowdNodeDepositReceivedResponse(Constants.NETWORK_PARAMETERS);

    private CrowdNodeAPIConfirmationHandler apiConfirmationHandler;

    void handleMetadata(Transaction tx) {
        metadataHandler.post(() -> {
            transactionMetadataProvider.syncTransactionBlocking(tx);
        });
    }

    private final ThrottlingWalletChangeListener walletEventListener = new ThrottlingWalletChangeListener(
            APPWIDGET_THROTTLE_MS) {

        @Override
        public void onThrottledWalletChanged() {
            updateAppWidget();
        }

        @Override
        public void onCoinsReceived(final Wallet wallet, final Transaction tx, final Coin prevBalance,
                                    final Coin newBalance) {

            final int bestChainHeight = blockChain.getBestChainHeight();
            final boolean replaying = bestChainHeight < config.getBestChainHeightEver() || config.isRestoringBackup();

            long now = new Date().getTime();
            long blockChainHeadTime = blockChain.getChainHead().getHeader().getTime().getTime();
            boolean insideTxExchangeRateTimeThreshold = (now - blockChainHeadTime) < TX_EXCHANGE_RATE_TIME_THRESHOLD_MS;

            log.info("onCoinsReceived: {}; rate: {}; replaying: {}; inside: {}, confid: {}; will update {}",
                    tx.getTxId(), tx.getExchangeRate(), replaying, insideTxExchangeRateTimeThreshold, tx.getConfidence().getConfidenceType(),
                    tx.getExchangeRate() == null && ((!replaying || insideTxExchangeRateTimeThreshold) || tx.getConfidence().getConfidenceType() == ConfidenceType.PENDING));

            // only set an exchange rate if the tx has no exchange rate and:
            //   1. the blockchain is not being rescanned nor the wallet is being restored OR
            //   2. the transaction is less than three hours old OR
            //   3. the transaction is not yet mined
            if (tx.getExchangeRate() == null && (!replaying
                    || insideTxExchangeRateTimeThreshold
                    || tx.getConfidence().getConfidenceType() == ConfidenceType.PENDING)) {
                try {
                    final org.dash.wallet.common.data.entity.ExchangeRate exchangeRate =
                            exchangeRatesDao.getRateSync(walletUIConfig.getExchangeCurrencyCodeBlocking());
                    if (exchangeRate != null) {
                        log.info("Setting exchange rate on received transaction.  Rate:  " + exchangeRate + " tx: " + tx.getTxId().toString());
                        tx.setExchangeRate(new ExchangeRate(Coin.COIN, exchangeRate.getFiat()));
                        application.saveWallet();
                    }
                } catch (Exception e) {
                    log.error("Failed to get exchange rate", e);
                }
            }

            transactionsReceived.incrementAndGet();

            final Address address = TransactionUtils.INSTANCE.getWalletAddressOfReceived(tx, wallet);
            final Coin amount = tx.getValue(wallet);
            final ConfidenceType confidenceType = tx.getConfidence().getConfidenceType();
            final boolean isRestoringBackup = application.getConfiguration().isRestoringBackup();

            handler.post(() -> {
                final boolean isReplayedTx = confidenceType == ConfidenceType.BUILDING && (replaying || isRestoringBackup);

                if (!isReplayedTx) {
                    if (depositReceivedResponse.matches(tx)) {
                        notificationService.showNotification(
                                "deposit_received",
                                getString(R.string.crowdnode_deposit_received),
                                null,
                                null,
                                new Intent(BlockchainServiceImpl.this, StakingActivity.class),
                                null
                        );
                    } else if (apiConfirmationHandler != null && apiConfirmationHandler.matches(tx)) {
                        apiConfirmationHandler.handle(tx);
                    } else if (passFilters(tx, wallet)) {
                        notifyCoinsReceived(address, amount, tx.getExchangeRate());
                    }
                }
            });

            handleMetadata(tx);
            updateAppWidget();
        }

        @Override
        public void onCoinsSent(final Wallet wallet, final Transaction tx, final Coin prevBalance,
                                final Coin newBalance) {
            transactionsReceived.incrementAndGet();

            log.info("onCoinsSent: {}", tx.getTxId());


            if(AssetLockTransaction.isAssetLockTransaction(tx) && tx.getPurpose() == Transaction.Purpose.UNKNOWN) {
                // Handle credit function transactions (username creation, topup, invites)
                AuthenticationGroupExtension authExtension =
                        (AuthenticationGroupExtension) wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID);
                AssetLockTransaction cftx = authExtension.getAssetLockTransaction(tx);

                long blockChainHeadTime = blockChain.getChainHead().getHeader().getTime().getTime();
                platformRepo.handleSentAssetLockTransaction(cftx, blockChainHeadTime);

                // TODO: if we detect a username creation that we haven't processed, should we?
            }

            handleMetadata(tx);
            updateAppWidget();
        }

        private Boolean passFilters(final Transaction tx, final Wallet wallet) {
            Coin amount = tx.getValue(wallet);
            final boolean isReceived = amount.signum() > 0;

            if (!isReceived) {
                return false;
            }

            boolean passFilters = false;

            for (TransactionFilter filter: crowdnodeFilters) {
                if (filter.matches(tx)) {
                    passFilters = true;
                    break;
                }
            }

            return passFilters;
        }
    };

    private final OnSharedPreferenceChangeListener sharedPrefsChangeListener = (sharedPreferences, key) -> {
        if (key.equals(Configuration.PREFS_KEY_CROWDNODE_PRIMARY_ADDRESS)) {
            registerCrowdNodeConfirmedAddressFilter();
        }
    };

    private boolean resetMNListsOnPeerGroupStart = false;

    private void notifyCoinsReceived(@Nullable final Address address, final Coin amount,
                                     @Nullable ExchangeRate exchangeRate) {
        if (notificationCount == 1)
            nm.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED);

        notificationCount++;
        notificationAccumulatedAmount = notificationAccumulatedAmount.add(amount);
        if (address != null && !notificationAddresses.contains(address))
            notificationAddresses.add(address);

        final MonetaryFormat btcFormat = config.getFormat();

        final String packageFlavor = packageInfoProvider.applicationPackageFlavor();
        String msgSuffix = packageFlavor != null ? " [" + packageFlavor + "]" : "";

        if (exchangeRate != null) {
            MonetaryFormat format = Constants.LOCAL_FORMAT.code(0,
                    PREFIX_ALMOST_EQUAL_TO + exchangeRate.fiat.getCurrencyCode());
            msgSuffix += " " + format.format(exchangeRate.coinToFiat(notificationAccumulatedAmount));
        }

        final String tickerMsg = getString(R.string.notification_coins_received_msg, btcFormat.format(amount))
                + msgSuffix;
        final String msg = getString(R.string.notification_coins_received_msg,
                btcFormat.format(notificationAccumulatedAmount)) + msgSuffix;

        final StringBuilder text = new StringBuilder();
        for (final Address notificationAddress : notificationAddresses) {
            if (text.length() > 0)
                text.append(", ");

            final String addressStr = notificationAddress.toString();
            final String label = AddressBookProvider.resolveLabel(getApplicationContext(), addressStr);
            text.append(label != null ? label : addressStr);
        }

        final NotificationCompat.Builder notification = new NotificationCompat.Builder(this,
                Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS);
        notification.setSmallIcon(R.drawable.ic_dash_d_white);
        notification.setTicker(tickerMsg);
        notification.setContentTitle(msg);
        if (text.length() > 0)
            notification.setContentText(text);
        notification.setContentIntent(PendingIntent.getActivity(this, 0, OnboardingActivity.createIntent(this), PendingIntent.FLAG_IMMUTABLE));
        notification.setNumber(notificationCount == 1 ? 0 : notificationCount);
        notification.setWhen(System.currentTimeMillis());
        notification.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.coins_received));
        nm.notify(Constants.NOTIFICATION_ID_COINS_RECEIVED, notification.build());
    }

    private final class PeerConnectivityListener
            implements PeerConnectedEventListener, PeerDisconnectedEventListener, OnSharedPreferenceChangeListener {
        private int peerCount;
        private AtomicBoolean stopped = new AtomicBoolean(false);

        public PeerConnectivityListener() {
            config.registerOnSharedPreferenceChangeListener(this);
        }

        public void stop() {
            stopped.set(true);

            config.unregisterOnSharedPreferenceChangeListener(this);

            nm.cancel(Constants.NOTIFICATION_ID_CONNECTED);
        }

        @Override
        public void onPeerConnected(final Peer peer, final int peerCount) {
            this.peerCount = peerCount;
            changed(peerCount);
        }

        @Override
        public void onPeerDisconnected(final Peer peer, final int peerCount) {
            this.peerCount = peerCount;
            changed(peerCount);
        }

        @Override
        public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
            if (Configuration.PREFS_KEY_CONNECTIVITY_NOTIFICATION.equals(key))
                changed(peerCount);
        }

        private void changed(final int numPeers) {
            if (stopped.get())
                return;
            NetworkStatus networkStatus = blockchainStateDataProvider.getNetworkStatus();
            if (numPeers > 0 && networkStatus == NetworkStatus.CONNECTING)
                blockchainStateDataProvider.setNetworkStatus(NetworkStatus.CONNECTED);
            else if (numPeers == 0 && networkStatus == NetworkStatus.DISCONNECTING)
                blockchainStateDataProvider.setNetworkStatus(NetworkStatus.DISCONNECTED);

            handler.post(() -> {
                final boolean connectivityNotificationEnabled = config.getConnectivityNotificationEnabled();

                if (!connectivityNotificationEnabled || numPeers == 0) {
                    nm.cancel(Constants.NOTIFICATION_ID_CONNECTED);
                } else {
                    final Notification.Builder notification = new Notification.Builder(BlockchainServiceImpl.this);
                    notification.setSmallIcon(R.drawable.stat_sys_peers, numPeers > 4 ? 4 : numPeers);
                    notification.setContentTitle(getString(R.string.app_name));
                    notification.setContentText(getString(R.string.notification_peers_connected_msg, numPeers));
                    notification.setContentIntent(PendingIntent.getActivity(BlockchainServiceImpl.this, 0,
                            OnboardingActivity.createIntent(BlockchainServiceImpl.this), PendingIntent.FLAG_IMMUTABLE));
                    notification.setWhen(System.currentTimeMillis());
                    notification.setOngoing(true);
                    nm.notify(Constants.NOTIFICATION_ID_CONNECTED, notification.build());
                }

                // send broadcast
                broadcastPeerState(numPeers);
            });
        }
    }

    private abstract class MyDownloadProgressTracker extends DownloadProgressTracker implements OnPreBlockProgressListener { }

    private final MyDownloadProgressTracker blockchainDownloadListener = new MyDownloadProgressTracker() {
        private final AtomicLong lastMessageTime = new AtomicLong(0);
        private long throttleDelay = -1;

        @Override
        public void onBlocksDownloaded(final Peer peer, final Block block, final FilteredBlock filteredBlock,
                                       final int blocksLeft) {
            super.onBlocksDownloaded(peer, block, filteredBlock, blocksLeft);
            postOrPostDelayed();
        }

        @Override
        public void onHeadersDownloaded(final Peer peer, final Block block,
                                        final int blocksLeft) {
            super.onHeadersDownloaded(peer, block, blocksLeft);
            postOrPostDelayed();
        }

        private final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                lastMessageTime.set(System.currentTimeMillis());
                log.debug("Runnable % = " + syncPercentage);

                config.maybeIncrementBestChainHeightEver(blockChain.getChainHead().getHeight());
                config.maybeIncrementBestHeaderHeightEver(headerChain.getChainHead().getHeight());
                if(config.isRestoringBackup()) {
                    long timeAgo = System.currentTimeMillis() - blockChain.getChainHead().getHeader().getTimeSeconds() * 1000;
                    //if the app was restoring a backup from a file or seed and block chain is nearly synced
                    //then turn off the restoring indicator
                    if(timeAgo < DateUtils.DAY_IN_MILLIS)
                        config.setRestoringBackup(false);
                }
                // this method is always called after progress or doneDownload
                updateBlockchainState();
            }
        };

        /*
            This method is called by super.onBlocksDownloaded when the percentage
            of the chain downloaded is 0.0, 1.0, 2.0, 3.0 .. 99.0% (whole numbers)

            The pct value is relative to the blocks that need to be downloaded to sync,
            rather than the relative to the entire blockchain.
         */
        @Override
        protected void progress(double pct, int blocksLeft, Date date) {
            super.progress(pct, blocksLeft, date);
            syncPercentage = pct > 0.0 ? (int)pct : 0;
            log.info("progress {}", syncPercentage);
            if (syncPercentage > 100) {
                syncPercentage = 100;
            }
        }

        /*
            This method is called by super.onBlocksDownloaded when the percentage
            of the chain downloaded is 100.0% (completely done)
        */
        @Override
        protected void doneDownload() {
            super.doneDownload();
            log.info("DoneDownload {}", syncPercentage);
            // if the chain is already synced from a previous session, then syncPercentage = 0
            // set to 100% so that observers will see that sync is completed
            syncPercentage = 100;
            updateBlockchainState();
        }

        @Override
        public void onMasterNodeListDiffDownloaded(Stage stage, @Nullable SimplifiedMasternodeListDiff mnlistdiff) {
            log.info("masternodeListDiffDownloaded:" + stage);
            if(peerGroup != null && peerGroup.getSyncStage() == PeerGroup.SyncStage.MNLIST) {
                super.onMasterNodeListDiffDownloaded(stage, mnlistdiff);
                startPreBlockPercent = syncPercentage;
                mnListDiffsReceived.incrementAndGet();
                postOrPostDelayed();
            }
        }

        private void postOrPostDelayed() {
            delayHandler.removeCallbacksAndMessages(null);
            if (throttleDelay == -1) {
                throttleDelay = application.isLowRamDevice() ? BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS : BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS / 4;
            }
            final long now = System.currentTimeMillis();
            if (now - lastMessageTime.get() > throttleDelay) {
                delayHandler.post(runnable);
            } else {
                delayHandler.postDelayed(runnable, throttleDelay);
            }
        }

        int totalPreblockStages = PreBlockStage.UpdateTotal.getValue();
        int startPreBlockPercent = 0;
        PreBlockStage lastPreBlockStage = PreBlockStage.None;

        @Override
        public void onPreBlockProgressUpdated(PreBlockStage stage) {
            if (stage == PreBlockStage.Starting && lastPreBlockStage == PreBlockStage.None) {
                startPreBlockPercent = syncPercentage;
            }
            if (preBlocksWeight > 0.99) {
                startPreBlockPercent = 0;
            }
            if (stage == PreBlockStage.StartRecovery && lastPreBlockStage == PreBlockStage.None) {
                startPreBlockPercent = syncPercentage;
                if (preBlocksWeight <= 0.10)
                    setPreBlocksWeight(0.20);
            }
            double increment = preBlocksWeight * stage.getValue() * 100.0 / PreBlockStage.Complete.getValue();
            if (increment > preBlocksWeight * 100)
                increment = preBlocksWeight * 100;

            log.debug("PreBlockDownload: " + increment + "%..." + preBlocksWeight + " " + stage.name() + " " + peerGroup.getSyncStage().name());
            if (peerGroup != null && peerGroup.getSyncStage() == PeerGroup.SyncStage.PREBLOCKS) {
                syncPercentage = (int)(startPreBlockPercent + increment);
                log.info("PreBlockDownload: " + syncPercentage + "%..." + peerGroup.getSyncStage().name());
                postOrPostDelayed();
            }
            lastPreBlockStage = stage;
        }
    };

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();

            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                final NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
                final boolean hasConnectivity = networkInfo != null && networkInfo.isConnected();

                if (log.isInfoEnabled()) {
                    final StringBuilder s = new StringBuilder("active network is ")
                            .append(hasConnectivity ? "up" : "down");
                    if (networkInfo != null) {
                        s.append(", type: ").append(networkInfo.getTypeName());
                        s.append(", state: ").append(networkInfo.getState()).append('/')
                                .append(networkInfo.getDetailedState());
                        final String extraInfo = networkInfo.getExtraInfo();
                        if (extraInfo != null)
                            s.append(", extraInfo: ").append(extraInfo);
                        final String reason = networkInfo.getReason();
                        if (reason != null)
                            s.append(", reason: ").append(reason);
                    }
                    log.info(s.toString());
                }

                if (hasConnectivity) {
                    impediments.remove(BlockchainState.Impediment.NETWORK);
                } else {
                    impediments.add(BlockchainState.Impediment.NETWORK);
                }

                updateBlockchainStateImpediments();
                check();
            } else if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                log.info("device storage low");
                impediments.add(BlockchainState.Impediment.STORAGE);
                updateBlockchainStateImpediments();
                check();
            } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                log.info("device storage ok");

                impediments.remove(BlockchainState.Impediment.STORAGE);
                updateBlockchainStateImpediments();
                check();
            }
        }

        @SuppressLint("Wakelock")
        private void check() {
            final Wallet wallet = application.getWallet();

            if (impediments.isEmpty() && peerGroup == null) {
                log.debug("acquiring wakelock");
                wakeLock.acquire();

                // consistency check
                final int walletLastBlockSeenHeight = wallet.getLastBlockSeenHeight();
                final int bestChainHeight = blockChain.getBestChainHeight();
                if (walletLastBlockSeenHeight != -1 && walletLastBlockSeenHeight != bestChainHeight) {
                    final String message = "wallet/blockchain out of sync: " + walletLastBlockSeenHeight + "/"
                            + bestChainHeight;
                    log.error(message);
                    CrashReporter.saveBackgroundTrace(new RuntimeException(message), packageInfoProvider.getPackageInfo());
                }

                wallet.getContext().initDashSync(getDir("masternode", MODE_PRIVATE).getAbsolutePath());

                log.info("starting peergroup");
                peerGroup = new PeerGroup(Constants.NETWORK_PARAMETERS, blockChain, headerChain);
                if (resetMNListsOnPeerGroupStart) {
                    resetMNListsOnPeerGroupStart = false;
                    application.getWallet().getContext().masternodeListManager.setBootstrap(mnlistinfoBootStrapStream, qrinfoBootStrapStream, SimplifiedMasternodeListManager.QUORUM_ROTATION_FORMAT_VERSION);
                    resetMNLists(true);
                }

                peerGroup.setDownloadTxDependencies(0); // recursive implementation causes StackOverflowError
                peerGroup.addWallet(wallet);
                peerGroup.setUserAgent(Constants.USER_AGENT, packageInfoProvider.getVersionName());
                peerGroup.addConnectedEventListener(peerConnectivityListener);
                peerGroup.addDisconnectedEventListener(peerConnectivityListener);

                final int maxConnectedPeers = application.maxConnectedPeers();

                final String trustedPeerHost = config.getTrustedPeerHost();
                final boolean hasTrustedPeer = trustedPeerHost != null;

                final boolean connectTrustedPeerOnly = hasTrustedPeer && config.getTrustedPeerOnly();
                peerGroup.setMaxConnections(connectTrustedPeerOnly ? 1 : maxConnectedPeers);
                peerGroup.setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS);
                peerGroup.setPeerDiscoveryTimeoutMillis(Constants.PEER_DISCOVERY_TIMEOUT_MS);

                peerGroup.addPeerDiscovery(new PeerDiscovery() {
                    //Keep Original code here for now
                    //private final PeerDiscovery normalPeerDiscovery = MultiplexingDiscovery
                    //        .forServices(Constants.NETWORK_PARAMETERS, 0);
                    private final PeerDiscovery normalPeerDiscovery = new MultiplexingDiscovery(Constants.NETWORK_PARAMETERS, peerDiscoveryList);


                    @Override
                    public InetSocketAddress[] getPeers(final long services, final long timeoutValue,
                                                        final TimeUnit timeoutUnit) throws PeerDiscoveryException {
                        final List<InetSocketAddress> peers = new LinkedList<InetSocketAddress>();

                        boolean needsTrimPeersWorkaround = false;

                        if (hasTrustedPeer) {
                            log.info(
                                    "trusted peer '" + trustedPeerHost + "'" + (connectTrustedPeerOnly ? " only" : ""));

                            final InetSocketAddress addr = new InetSocketAddress(trustedPeerHost,
                                    Constants.NETWORK_PARAMETERS.getPort());
                            if (addr.getAddress() != null) {
                                peers.add(addr);
                                needsTrimPeersWorkaround = true;
                            }
                        }

                        if (!connectTrustedPeerOnly) {
                            // First use the masternode list that is included
                            try {
                                SimplifiedMasternodeList mnlist = org.bitcoinj.core.Context.get().masternodeListManager.getListAtChainTip();
                                MasternodePeerDiscovery discovery = new MasternodePeerDiscovery(mnlist);
                                peers.addAll(Arrays.asList(discovery.getPeers(services, timeoutValue, timeoutUnit)));
                            } catch (PeerDiscoveryException x) {
                                //swallow and continue with another method of connection
                                log.info("DMN List peer discovery failed: "+ x.getMessage());
                            }

                            // default masternode list
                            if(peers.size() < MINIMUM_PEER_COUNT) {
                                String [] defaultMNList = Constants.NETWORK_PARAMETERS.getDefaultMasternodeList();
                                if (defaultMNList != null || defaultMNList.length != 0) {
                                    log.info("DMN peer discovery returned less than 16 nodes.  Adding default DMN peers to the list to increase connections");
                                    MasternodePeerDiscovery discovery = new MasternodePeerDiscovery(defaultMNList, Constants.NETWORK_PARAMETERS.getPort());
                                    peers.addAll(Arrays.asList(discovery.getPeers(services, timeoutValue, timeoutUnit)));
                                } else {
                                    log.info("DNS peer discovery returned less than 16 nodes.  Unable to add seed peers (it is not specified for this network).");
                                }
                            }

                            // seed nodes
                            if(peers.size() < MINIMUM_PEER_COUNT) {
                                if (Constants.NETWORK_PARAMETERS.getAddrSeeds() != null) {
                                    log.info("Static DMN peer discovery returned less than 16 nodes.  Adding seed peers to the list to increase connections");
                                    peers.addAll(Arrays.asList(seedPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)));
                                } else {
                                    log.info("DNS peer discovery returned less than 16 nodes.  Unable to add seed peers (it is not specified for this network).");
                                }
                            }

                            if(peers.size() < MINIMUM_PEER_COUNT) {
                                log.info("Masternode peer discovery returned less than 16 nodes.  Adding DMN peers to the list to increase connections");

                                try {
                                    peers.addAll(
                                            Arrays.asList(normalPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)));
                                } catch (PeerDiscoveryException x) {
                                    //swallow and continue with another method of connection, if one exists.
                                    log.info("DNS peer discovery failed: "+ x.getMessage());
                                    if(x.getCause() != null)
                                        log.info(  "cause:  " + x.getCause().getMessage());
                                }
                            }
                        }

                        // workaround because PeerGroup will shuffle peers
                        if (needsTrimPeersWorkaround)
                            while (peers.size() >= maxConnectedPeers)
                                peers.remove(peers.size() - 1);

                        return peers.toArray(new InetSocketAddress[0]);
                    }

                    @Override
                    public void shutdown() {
                        normalPeerDiscovery.shutdown();
                    }
                });

                peerGroup.addPreBlocksDownloadListener(executor, preBlocksDownloadListener);
                // Use our custom risk analysis that allows v2 tx with absolute LockTime
                riskAnalyzer = new AllowLockTimeRiskAnalysis.Analyzer(peerGroup);
                wallet.setRiskAnalyzer(riskAnalyzer);

                // start peergroup
                blockchainStateDataProvider.setNetworkStatus(NetworkStatus.CONNECTING);
                peerGroup.startAsync();
                peerGroup.startBlockChainDownload(blockchainDownloadListener);
                platformSyncService.addPreBlockProgressListener(blockchainDownloadListener);

            } else if (!impediments.isEmpty() && peerGroup != null) {
                blockchainStateDataProvider.setNetworkStatus(NetworkStatus.NOT_AVAILABLE);
                application.getWallet().getContext().close();
                log.info("stopping peergroup");
                peerGroup.removeDisconnectedEventListener(peerConnectivityListener);
                peerGroup.removeConnectedEventListener(peerConnectivityListener);
                peerGroup.removePreBlocksDownloadedListener(preBlocksDownloadListener);
                peerGroup.removeWallet(wallet);
                platformSyncService.removePreBlockProgressListener(blockchainDownloadListener);
                peerGroup.stopAsync();
                // use the offline risk analyzer
                wallet.setRiskAnalyzer(new AllowLockTimeRiskAnalysis.OfflineAnalyzer(config.getBestHeightEver(), System.currentTimeMillis()/1000));
                riskAnalyzer.shutdown();

                peerGroup = null;

                log.debug("releasing wakelock");
                wakeLock.release();
            }
        }
    };

    private final static class ActivityHistoryEntry {
        public final int numTransactionsReceived;
        public final int numBlocksDownloaded;
        public final int numHeadersDownloaded;
        public final int numMnListDiffsDownloaded;

        public ActivityHistoryEntry(final int numTransactionsReceived, final int numBlocksDownloaded,
                                    final int numHeadersDownloaded, final int numMnListDiffsDownloaded) {
            this.numTransactionsReceived = numTransactionsReceived;
            this.numBlocksDownloaded = numBlocksDownloaded;
            this.numHeadersDownloaded = numHeadersDownloaded;
            this.numMnListDiffsDownloaded = numMnListDiffsDownloaded;
        }

        @Override
        public String toString() {
            return numTransactionsReceived + "/" + numBlocksDownloaded + "/" + numHeadersDownloaded + "/" + numMnListDiffsDownloaded;
        }
    }

    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        private int lastChainHeight = 0;
        private int lastHeaderHeight = 0;
        private final List<ActivityHistoryEntry> activityHistory = new LinkedList<ActivityHistoryEntry>();

        @Override
        public void onReceive(final Context context, final Intent intent) {
            final int chainHeight = blockChain.getBestChainHeight();
            final int headerHeight = headerChain.getBestChainHeight();

            if (lastChainHeight > 0 || lastHeaderHeight > 0) {
                final int numBlocksDownloaded = chainHeight - lastChainHeight;
                final int numTransactionsReceived = transactionsReceived.getAndSet(0);
                // instead of counting headers, count header messages which contain up to 2000 headers
                final int numHeadersDownloaded = headerHeight - lastHeaderHeight;
                final int numMnListDiffsDownloaded = mnListDiffsReceived.getAndSet(0);

                // push history
                activityHistory.add(0, new ActivityHistoryEntry(numTransactionsReceived, numBlocksDownloaded, numHeadersDownloaded, numMnListDiffsDownloaded));

                // trim
                while (activityHistory.size() > MAX_HISTORY_SIZE)
                    activityHistory.remove(activityHistory.size() - 1);

                // print
                final StringBuilder builder = new StringBuilder();
                for (final ActivityHistoryEntry entry : activityHistory) {
                    if (builder.length() > 0)
                        builder.append(", ");
                    builder.append(entry);
                }
                log.info("History of transactions/blocks/headers/mnlistdiff: " +
                        (mixingStatus == MixingStatus.MIXING ? "[mixing] " : "") + builder);

                // determine if block and transaction activity is idling
                boolean isIdle = false;
                if (activityHistory.size() >= MIN_COLLECT_HISTORY) {
                    isIdle = true;
                    for (int i = 0; i < activityHistory.size(); i++) {
                        final ActivityHistoryEntry entry = activityHistory.get(i);
                        final boolean blocksActive = entry.numBlocksDownloaded > 0 && i <= IDLE_BLOCK_TIMEOUT_MIN;
                        final boolean transactionsActive = entry.numTransactionsReceived > 0
                                && i <= IDLE_TRANSACTION_TIMEOUT_MIN;
                        final boolean headersActive = entry.numHeadersDownloaded > 0 && i <= IDLE_HEADER_TIMEOUT_MIN;
                        final boolean mnListDiffsActive = entry.numMnListDiffsDownloaded > 0 && i <= IDLE_MNLIST_TIMEOUT_MIN;

                        if (blocksActive || transactionsActive || headersActive || mnListDiffsActive) {
                            isIdle = false;
                            break;
                        }
                    }
                }

                // if idling, shutdown service
                if (isIdle && mixingStatus != MixingStatus.MIXING) {
                    log.info("idling detected, stopping service");
                    stopSelf();
                }
            }

            lastChainHeight = chainHeight;
            lastHeaderHeight = headerHeight;
        }
    };

    public class LocalBinder extends Binder {
        public BlockchainServiceImpl getService() {
            return BlockchainServiceImpl.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(final Intent intent) {
        super.onBind(intent);
        log.debug(".onBind()");

        return mBinder;
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        log.debug(".onUnbind()");

        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        serviceCreatedAt = System.currentTimeMillis();
        log.debug(".onCreate()");

        super.onCreate();

        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        final String lockName = getPackageName() + " blockchain sync";

        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground();
        }

        final Wallet wallet = application.getWallet();

        peerConnectivityListener = new PeerConnectivityListener();

        broadcastPeerState(0);

        blockChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME);
        final boolean blockChainFileExists = blockChainFile.exists();

        headerChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.HEADERS_FILENAME);

        mnlistinfoBootStrapStream = loadStream(Constants.Files.MNLIST_BOOTSTRAP_FILENAME);
        qrinfoBootStrapStream = loadStream(Constants.Files.QRINFO_BOOTSTRAP_FILENAME);

        if (!blockChainFileExists) {
            log.info("blockchain does not exist, resetting wallet");
            wallet.reset();
            resetMNLists(false);
            resetMNListsOnPeerGroupStart = true;
        }

        try {
            blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
            blockStore.getChainHead(); // detect corruptions as early as possible

            headerStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, headerChainFile);
            headerStore.getChainHead(); // detect corruptions as early as possible

            final long earliestKeyCreationTime = wallet.getEarliestKeyCreationTime();

            if (!blockChainFileExists && earliestKeyCreationTime > 0) {
                try {
                    final Stopwatch watch = Stopwatch.createStarted();
                    InputStream checkpointsInputStream = getAssets().open(Constants.Files.CHECKPOINTS_FILENAME);
                    CheckpointManager.checkpoint(Constants.NETWORK_PARAMETERS, checkpointsInputStream, blockStore,
                            earliestKeyCreationTime);
                    //the headerStore should be set to the most recent checkpoint
                    checkpointsInputStream = getAssets().open(Constants.Files.CHECKPOINTS_FILENAME);
                    CheckpointManager.checkpoint(Constants.NETWORK_PARAMETERS, checkpointsInputStream, headerStore,
                            System.currentTimeMillis() / 1000);
                    watch.stop();
                    log.info("checkpoints loaded from '{}', took {}", Constants.Files.CHECKPOINTS_FILENAME, watch);
                } catch (final IOException x) {
                    log.error("problem reading checkpoints, continuing without", x);
                }
            }
        } catch (final BlockStoreException x) {
            blockChainFile.delete();
            headerChainFile.delete();
            resetMNLists(false);

            final String msg = "blockstore cannot be created";
            log.error(msg, x);
            throw new Error(msg, x);
        }

        try {
            blockChain = new BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore);
            headerChain = new BlockChain(Constants.NETWORK_PARAMETERS, headerStore);
            blockchainStateDataProvider.setBlockChain(blockChain);
        } catch (final BlockStoreException x) {
            throw new Error("blockchain cannot be created", x);
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        registerReceiver(connectivityReceiver, intentFilter); // implicitly start PeerGroup

        application.getWallet().addCoinsReceivedEventListener(Threading.SAME_THREAD, walletEventListener);
        application.getWallet().addCoinsSentEventListener(Threading.SAME_THREAD, walletEventListener);
        application.getWallet().addChangeEventListener(Threading.SAME_THREAD, walletEventListener);
        config.registerOnSharedPreferenceChangeListener(sharedPrefsChangeListener);

        registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));

        peerDiscoveryList.add(dnsDiscovery);

        if (Constants.SUPPORTS_PLATFORM) {
            platformRepo.getPlatform().setMasternodeListManager(application.getWallet().getContext().masternodeListManager);
            platformSyncService.resume();
        }

        updateAppWidget();
        FlowExtKt.observe(blockchainStateDao.observeState(), this, (blockchainState, continuation) -> {
            handleBlockchainStateNotification(blockchainState, mixingStatus, mixingProgress);
            return null;
        });
        registerCrowdNodeConfirmedAddressFilter();

        FlowExtKt.observe(coinJoinService.observeMixingState(), this, (mixingStatus, continuation) -> {
            handleBlockchainStateNotification(blockchainState, mixingStatus, mixingProgress);
            return null;
        });

        FlowExtKt.observe(coinJoinService.observeMixingProgress(), this, (mixingProgress, continuation) -> {
            handleBlockchainStateNotification(blockchainState, mixingStatus, mixingProgress);
            return null;
        });
    }

    private Notification createCoinJoinNotification() {
        Coin mixedBalance = ((WalletEx)application.getWallet()).getCoinJoinBalance();
        Coin totalBalance = application.getWallet().getBalance();
        Intent notificationIntent = OnboardingActivity.createIntent(this);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        DecimalFormat decimalFormat = new DecimalFormat("0.000");
        int statusStringId = R.string.error;
        switch(mixingStatus) {
            case MIXING:
                statusStringId = R.string.coinjoin_mixing;
                break;
            case PAUSED:
                statusStringId = R.string.coinjoin_paused;
                break;
            case FINISHED:
                statusStringId = R.string.coinjoin_progress_finished;
                break;
        }
        final String message = getString(
                R.string.coinjoin_progress,
                getString(statusStringId),
                mixingProgress.intValue(),
                decimalFormat.format(MonetaryExtKt.toBigDecimal(mixedBalance)),
                decimalFormat.format(MonetaryExtKt.toBigDecimal(totalBalance))
        );

        return new NotificationCompat.Builder(this,
                Constants.NOTIFICATION_CHANNEL_ID_ONGOING)
                .setSmallIcon(R.drawable.ic_dash_d_white)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setContentIntent(pendingIntent).build();
    }

    private void resetMNLists(boolean requestFreshList) {
        try {
            SimplifiedMasternodeListManager manager = application.getWallet().getContext().masternodeListManager;
            if (manager != null)
                manager.resetMNList(true, requestFreshList);
        } catch (RuntimeException x) {
            // swallow this exception.  It is thrown when there is not a bootstrap file
            // there is not a bootstrap mnlist file for testnet
            log.info("error resetting masternode list with bootstrap files", x);
        }
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent != null) {
            //Restart service as a Foreground Service if it's synchronizing the blockchain
            Bundle extras = intent.getExtras();
            if (extras != null && extras.containsKey(START_AS_FOREGROUND_EXTRA)) {
                startForeground();
            }

            log.info("service start command: " + intent + (intent.hasExtra(Intent.EXTRA_ALARM_COUNT)
                    ? " (alarm count: " + intent.getIntExtra(Intent.EXTRA_ALARM_COUNT, 0) + ")" : ""));

            final String action = intent.getAction();

            if (BlockchainService.ACTION_CANCEL_COINS_RECEIVED.equals(action)) {
                notificationCount = 0;
                notificationAccumulatedAmount = Coin.ZERO;
                notificationAddresses.clear();

                nm.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED);
            } else if (BlockchainService.ACTION_RESET_BLOCKCHAIN.equals(action)) {
                log.info("will remove blockchain on service shutdown");

                resetBlockchainOnShutdown = true;
                stopSelf();
            } else if (BlockchainService.ACTION_WIPE_WALLET.equals(action)) {
                log.info("will remove blockchain and delete walletFile on service shutdown");

                deleteWalletFileOnShutdown = true;
                stopSelf();
            } else if (BlockchainService.ACTION_BROADCAST_TRANSACTION.equals(action)) {
                final Sha256Hash hash = Sha256Hash
                        .wrap(intent.getByteArrayExtra(BlockchainService.ACTION_BROADCAST_TRANSACTION_HASH));
                final Transaction tx = application.getWallet().getTransaction(hash);

                if (peerGroup != null) {
                    log.info("broadcasting transaction " + tx.getHashAsString());
                    int count = peerGroup.numConnectedPeers();
                    int minimum = peerGroup.getMinBroadcastConnections();
                    //if the number of peers is <= 3, then only require that number of peers to send
                    //if the number of peers is 0, then require 3 peers (default min connections)
                    if(count > 0 && count <= 3)
                        minimum = count;

                    peerGroup.broadcastTransaction(tx, minimum, true);
                } else {
                    log.info("peergroup not available, not broadcasting transaction {}", tx.getTxId());
                    tx.getConfidence().setPeerInfo(0, 1);
                }
            } else if(BlockchainService.ACTION_RESET_BLOOMFILTERS.equals(action)) {
                if (peerGroup != null) {
                    log.info("recalculating bloom filters");
                    peerGroup.recalculateFastCatchupAndFilter(PeerGroup.FilterRecalculateMode.FORCE_SEND_FOR_REFRESH);
                } else {
                    log.info("peergroup not available, not recalculating bloom filers");
                }
            }
        } else {
            log.warn("service restart, although it was started as non-sticky");
        }

        return START_NOT_STICKY;
    }

    private void startForeground() {
        //Shows ongoing notification promoting service to foreground service and
        //preventing it from being killed in Android 26 or later
        Notification notification = createNetworkSyncNotification(null);
        startForeground(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification);
        foregroundService = ForegroundService.BLOCKCHAIN_SYNC;
    }

    private void startForegroundCoinJoin() {
        // Shows ongoing notification promoting service to foreground service and
        // preventing it from being killed in Android 26 or later
        Notification notification = createCoinJoinNotification();
        startForeground(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification);
        foregroundService = ForegroundService.COINJOIN_MIXING;
    }

    @Override
    public void onDestroy() {
        log.info(".onDestroy()");

        WalletApplication.scheduleStartBlockchainService(this);  //disconnect feature

        unregisterReceiver(tickReceiver);

        application.getWallet().removeChangeEventListener(walletEventListener);
        application.getWallet().removeCoinsSentEventListener(walletEventListener);
        application.getWallet().removeCoinsReceivedEventListener(walletEventListener);
        config.unregisterOnSharedPreferenceChangeListener(sharedPrefsChangeListener);

        unregisterReceiver(connectivityReceiver);

        platformSyncService.shutdown();

        if (peerGroup != null) {
            application.getWallet().getContext().close();
            peerGroup.removeDisconnectedEventListener(peerConnectivityListener);
            peerGroup.removeConnectedEventListener(peerConnectivityListener);
            peerGroup.removeWallet(application.getWallet());
            platformSyncService.removePreBlockProgressListener(blockchainDownloadListener);
            blockchainStateDataProvider.setNetworkStatus(NetworkStatus.DISCONNECTING);
            peerGroup.stop();
            blockchainStateDataProvider.setNetworkStatus(NetworkStatus.STOPPED);
            application.getWallet().setRiskAnalyzer(defaultRiskAnalyzer);
            riskAnalyzer.shutdown();

            log.info("peergroup stopped");
        }

        peerConnectivityListener.stop();

        delayHandler.removeCallbacksAndMessages(null);

        try {
            blockStore.close();
            headerStore.close();
            blockchainStateDataProvider.setBlockChain(null);
        } catch (final BlockStoreException x) {
            throw new RuntimeException(x);
        }

        if (!deleteWalletFileOnShutdown) {
            application.saveWallet();
        }

        if (wakeLock.isHeld()) {
            log.debug("wakelock still held, releasing");
            wakeLock.release();
        }

        if (resetBlockchainOnShutdown || deleteWalletFileOnShutdown) {
            log.info("removing blockchain");
            //noinspection ResultOfMethodCallIgnored
            blockChainFile.delete();
            //noinspection ResultOfMethodCallIgnored
            headerChainFile.delete();
            resetMNLists(false);
            if (deleteWalletFileOnShutdown) {
                log.info("removing wallet file and app data");
                application.finalizeWipe();
            }
            //Clear the blockchain identity
            WalletApplicationExt.INSTANCE.clearDatabases(application, false);
        }

        closeStream(mnlistinfoBootStrapStream);
        closeStream(qrinfoBootStrapStream);

        super.onDestroy();

        log.info("service was up for " + ((System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60) + " minutes");
    }

    @Override
    public void onTrimMemory(final int level) {
        log.info("onTrimMemory({}) called", level);

        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            log.warn("low memory detected, stopping service");
            stopSelf();
        }
    }

    private Notification createNetworkSyncNotification(BlockchainState blockchainState) {
        Intent notificationIntent = OnboardingActivity.createIntent(this);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final String message = (blockchainState != null)
                ? BlockchainStateUtils.getSyncStateString(blockchainState, this)
                : getString(R.string.blockchain_state_progress_downloading, 0);

        return new NotificationCompat.Builder(this,
                Constants.NOTIFICATION_CHANNEL_ID_ONGOING)
                .setSmallIcon(R.drawable.ic_dash_d_white)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setContentIntent(pendingIntent).build();
    }

    private void updateBlockchainStateImpediments() {
        blockchainStateDataProvider.updateImpediments(impediments);
    }

    private void updateBlockchainState() {
        blockchainStateDataProvider.updateBlockchainState(blockChain, impediments, percentageSync());
    }

    @Override
    public List<Peer> getConnectedPeers() {
        if (peerGroup != null)
            return peerGroup.getConnectedPeers();
        else
            return null;
    }

    @Override
    public List<StoredBlock> getRecentBlocks(final int maxBlocks) {
        final List<StoredBlock> blocks = new ArrayList<StoredBlock>(maxBlocks);

        try {
            StoredBlock block = blockChain.getChainHead();

            while (block != null) {
                blocks.add(block);

                if (blocks.size() >= maxBlocks)
                    break;

                block = block.getPrev(blockStore);
            }
        } catch (final BlockStoreException x) {
            // swallow
        }

        return blocks;
    }

    private void broadcastPeerState(final int numPeers) {
        final Intent broadcast = new Intent(ACTION_PEER_STATE);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(ACTION_PEER_STATE_NUM_PEERS, numPeers);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void handleBlockchainStateNotification(BlockchainState blockchainState, MixingStatus mixingStatus, double mixingProgress) {
        // send this out for the Network Monitor, other activities observe the database
        final Intent broadcast = new Intent(ACTION_BLOCKCHAIN_STATE);
        broadcast.setPackage(getPackageName());
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        // log.info("handle blockchain state notification: {}, {}", foregroundService, mixingStatus);
        this.mixingProgress = mixingProgress;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && blockchainState != null
                && blockchainState.getBestChainDate() != null) {
            //Handle Ongoing notification state
            boolean syncing = blockchainState.getBestChainDate().getTime() < (Utils.currentTimeMillis() - DateUtils.HOUR_IN_MILLIS); //1 hour
            if (!syncing && blockchainState.getBestChainHeight() == config.getBestChainHeightEver() && mixingStatus != MixingStatus.MIXING) {
                //Remove ongoing notification if blockchain sync finished
                stopForeground(true);
                foregroundService = ForegroundService.NONE;
                nm.cancel(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC);
            } else if (blockchainState.getReplaying() || syncing) {
                //Shows ongoing notification when synchronizing the blockchain
                Notification notification = createNetworkSyncNotification(blockchainState);
                nm.notify(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification);
            } else if (mixingStatus == MixingStatus.MIXING || mixingStatus == MixingStatus.PAUSED) {
                log.info("foreground service: {}", foregroundService);
                if (foregroundService == ForegroundService.NONE) {
                    log.info("foreground service not active, create notification");
                    startForegroundCoinJoin();
                    //Notification notification = createCoinJoinNotification();
                    //nm.notify(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification);
                    foregroundService = ForegroundService.COINJOIN_MIXING;
                } else {
                    log.info("foreground service active, update notification");
                    Notification notification = createCoinJoinNotification();
                    //nm.cancel(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC);
                    nm.notify(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification);
                }
            }
        }
        this.blockchainState = blockchainState;
        this.mixingStatus = mixingStatus;
    }

    private int percentageSync() {
        return syncPercentage;
    }

    private void updateAppWidget() {
        Coin balance = application.getWallet().getBalance(Wallet.BalanceType.ESTIMATED);
        WalletBalanceWidgetProvider.updateWidgets(BlockchainServiceImpl.this, balance);
    }

    public void forceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(this, BlockchainServiceImpl.class);
            ContextCompat.startForegroundService(this, intent);
            // call startForeground just after startForegroundService.
            startForeground();
        }
    }

    private PreBlocksDownloadListener preBlocksDownloadListener = new PreBlocksDownloadListener() {
        @Override
        public void onPreBlocksDownload(Peer peer) {
            log.info("onPreBlocksDownload using peer {}", peer);
            platformSyncService.preBlockDownload(peerGroup.getPreBlockDownloadFuture());
        }
    };

    private void registerCrowdNodeConfirmedAddressFilter() {
        String apiAddressStr = config.getCrowdNodeAccountAddress();
        String primaryAddressStr = config.getCrowdNodePrimaryAddress();

        if (!apiAddressStr.isEmpty() && !primaryAddressStr.isEmpty()) {
            Address apiAddress = Address.fromBase58(Constants.NETWORK_PARAMETERS, apiAddressStr);
            Address primaryAddress = Address.fromBase58(Constants.NETWORK_PARAMETERS, primaryAddressStr);

            apiConfirmationHandler = new CrowdNodeAPIConfirmationHandler(
                    apiAddress,
                    primaryAddress,
                    crowdNodeBlockchainApi,
                    notificationService,
                    crowdNodeConfig,
                    getResources(),
                    new Intent(this, StakingActivity.class)
            );
        } else {
            apiConfirmationHandler = null;
        }
    }

    InputStream loadStream(String filename) {
        InputStream stream = null;
        try {
            stream = getAssets().open(filename);
        } catch (IOException x) {
            log.warn("cannot load the bootstrap stream: {}", x.getMessage());
        }
        return stream;
    }


    private void closeStream(InputStream mnlistinfoBootStrapStream) {
        if (mnlistinfoBootStrapStream != null) {
            try {
                mnlistinfoBootStrapStream.close();
            } catch (IOException x) {
                //do nothing
            }
        }
    }
}
