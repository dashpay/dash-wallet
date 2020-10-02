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
import androidx.lifecycle.Observer;
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
import org.bitcoinj.core.listeners.PeerDataEventListener;
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener;
import org.bitcoinj.evolution.SimplifiedMasternodeList;
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
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
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

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.data.BlockchainStateDao;
import de.schildbach.wallet.ui.OnboardingActivity;
import de.schildbach.wallet.util.BlockchainStateUtils;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

import static org.dash.wallet.common.Constants.PREFIX_ALMOST_EQUAL_TO;

/**
 * @author Andreas Schildbach
 */
public class BlockchainServiceImpl extends LifecycleService implements BlockchainService {

    private WalletApplication application;
    private Configuration config;

    private BlockStore blockStore;
    private File blockChainFile;
    private BlockChain blockChain;
    private InputStream bootStrapStream;
    @Nullable
    private PeerGroup peerGroup;

    private final Handler handler = new Handler();
    private final Handler delayHandler = new Handler();
    private WakeLock wakeLock;

    private PeerConnectivityListener peerConnectivityListener;
    private NotificationManager nm;
    private ConnectivityManager connectivityManager;
    private final Set<BlockchainState.Impediment> impediments = EnumSet.noneOf(BlockchainState.Impediment.class);
    private int notificationCount = 0;
    private Coin notificationAccumulatedAmount = Coin.ZERO;
    private final List<Address> notificationAddresses = new LinkedList<Address>();
    private AtomicInteger transactionsReceived = new AtomicInteger();
    private long serviceCreatedAt;
    private boolean resetBlockchainOnShutdown = false;
    private boolean deleteWalletFileOnShutdown = false;

    //Settings to bypass dashj default dns seeds
    private final SeedPeers seedPeerDiscovery = new SeedPeers(Constants.NETWORK_PARAMETERS);
    private final DnsDiscovery dnsDiscovery = new DnsDiscovery(Constants.DNS_SEED, Constants.NETWORK_PARAMETERS);
    ArrayList<PeerDiscovery> peerDiscoveryList = new ArrayList<>(2);
    private final static int MINIMUM_PEER_COUNT = 16;

    private static final int MIN_COLLECT_HISTORY = 2;
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
            final boolean replaying = bestChainHeight < config.getBestChainHeightEver();

            long now = new Date().getTime();
            long blockChainHeadTime = blockChain.getChainHead().getHeader().getTime().getTime();
            boolean insideTxExchangeRateTimeThreshold = (now - blockChainHeadTime) < TX_EXCHANGE_RATE_TIME_THRESHOLD_MS;

            if (tx.getExchangeRate() == null && ((!replaying || insideTxExchangeRateTimeThreshold) || tx.getConfidence().getConfidenceType() == ConfidenceType.PENDING)) {
                try {
                    final de.schildbach.wallet.rates.ExchangeRate exchangeRate = AppDatabase.getAppDatabase()
                            .exchangeRatesDao().getRateSync(config.getExchangeCurrencyCode());
                    if (exchangeRate != null) {
                        log.info("Setting exchange rate on received transaction.  Rate:  " + exchangeRate.toString() + " tx: " + tx.getHashAsString());
                        tx.setExchangeRate(new ExchangeRate(Coin.COIN, exchangeRate.getFiat()));
                        application.saveWallet();
                    }
                } catch (Exception e) {
                    log.error("Failed to get exchange rate", e);
                }
            }

            transactionsReceived.incrementAndGet();


            final Address address = WalletUtils.getWalletAddressOfReceived(tx, wallet);
            final Coin amount = tx.getValue(wallet);
            final ConfidenceType confidenceType = tx.getConfidence().getConfidenceType();
            final boolean isRestoringBackup = application.getConfiguration().isRestoringBackup();

            handler.post(new Runnable() {
                @Override
                public void run() {
                    final boolean isReceived = amount.signum() > 0;
                    final boolean isReplayedTx = confidenceType == ConfidenceType.BUILDING && (replaying || isRestoringBackup);

                    if (isReceived && !isReplayedTx)
                        notifyCoinsReceived(address, amount, tx.getExchangeRate());
                }
            });
            updateAppWidget();
        }

        @Override
        public void onCoinsSent(final Wallet wallet, final Transaction tx, final Coin prevBalance,
                final Coin newBalance) {
            transactionsReceived.incrementAndGet();
            updateAppWidget();
        }
    };

    private void notifyCoinsReceived(@Nullable final Address address, final Coin amount,
                                     @Nullable ExchangeRate exchangeRate) {
        if (notificationCount == 1)
            nm.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED);

        notificationCount++;
        notificationAccumulatedAmount = notificationAccumulatedAmount.add(amount);
        if (address != null && !notificationAddresses.contains(address))
            notificationAddresses.add(address);

        final MonetaryFormat btcFormat = config.getFormat();

        final String packageFlavor = application.applicationPackageFlavor();
        String msgSuffix = packageFlavor != null ? " [" + packageFlavor + "]" : "";

        if (exchangeRate != null) {
            exchangeRate.coinToFiat(amount);
            MonetaryFormat format = Constants.LOCAL_FORMAT.code(0,
                    PREFIX_ALMOST_EQUAL_TO + exchangeRate.fiat.getCurrencyCode());
            msgSuffix += " " + format.format(exchangeRate.coinToFiat(amount));
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
        notification.setSmallIcon(R.drawable.ic_dash_d_white_bottom);
        notification.setTicker(tickerMsg);
        notification.setContentTitle(msg);
        if (text.length() > 0)
            notification.setContentText(text);
        notification.setContentIntent(PendingIntent.getActivity(this, 0, OnboardingActivity.createIntent(this), 0));
        notification.setNumber(notificationCount == 1 ? 0 : notificationCount);
        notification.setWhen(System.currentTimeMillis());
        notification.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.coins_received));
        nm.notify(Constants.NOTIFICATION_ID_COINS_RECEIVED, notification.getNotification());
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

            handler.post(new Runnable() {
                @Override
                public void run() {
                    final boolean connectivityNotificationEnabled = config.getConnectivityNotificationEnabled();

                    if (!connectivityNotificationEnabled || numPeers == 0) {
                        nm.cancel(Constants.NOTIFICATION_ID_CONNECTED);
                    } else {
                        final Notification.Builder notification = new Notification.Builder(BlockchainServiceImpl.this);
                        notification.setSmallIcon(R.drawable.stat_sys_peers, numPeers > 4 ? 4 : numPeers);
                        notification.setContentTitle(getString(R.string.app_name));
                        notification.setContentText(getString(R.string.notification_peers_connected_msg, numPeers));
                        notification.setContentIntent(PendingIntent.getActivity(BlockchainServiceImpl.this, 0,
                                OnboardingActivity.createIntent(BlockchainServiceImpl.this), 0));
                        notification.setWhen(System.currentTimeMillis());
                        notification.setOngoing(true);
                        nm.notify(Constants.NOTIFICATION_ID_CONNECTED, notification.getNotification());
                    }

                    // send broadcast
                    broadcastPeerState(numPeers);
                }
            });
        }
    }

    private final PeerDataEventListener blockchainDownloadListener = new DownloadProgressTracker() {
        private final AtomicLong lastMessageTime = new AtomicLong(0);

        @Override
        public void onBlocksDownloaded(final Peer peer, final Block block, final FilteredBlock filteredBlock,
                final int blocksLeft) {
            super.onBlocksDownloaded(peer, block, filteredBlock, blocksLeft);
            delayHandler.removeCallbacksAndMessages(null);

            final long now = System.currentTimeMillis();
            if (now - lastMessageTime.get() > BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS)
                delayHandler.post(runnable);
            else
                delayHandler.postDelayed(runnable, BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS);
        }

        private final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                lastMessageTime.set(System.currentTimeMillis());

                config.maybeIncrementBestChainHeightEver(blockChain.getChainHead().getHeight());
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
        }

        /*
            This method is called by super.onBlocksDownloaded when the percentage
            of the chain downloaded is 100.0% (completely done)
        */
        @Override
        protected void doneDownload() {
            super.doneDownload();
            syncPercentage = 100;
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
                    CrashReporter.saveBackgroundTrace(new RuntimeException(message), application.packageInfo());
                }

                log.info("starting peergroup");
                peerGroup = new PeerGroup(Constants.NETWORK_PARAMETERS, blockChain);
                peerGroup.setDownloadTxDependencies(0); // recursive implementation causes StackOverflowError
                peerGroup.addWallet(wallet);
                peerGroup.setUserAgent(Constants.USER_AGENT, application.packageInfo().versionName);
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

                            if(peers.size() < MINIMUM_PEER_COUNT) {
                                if (Constants.NETWORK_PARAMETERS.getAddrSeeds() != null) {
                                    log.info("DNM peer discovery returned less than 16 nodes.  Adding seed peers to the list to increase connections");
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

                // start peergroup
                peerGroup.startAsync();
                peerGroup.startBlockChainDownload(blockchainDownloadListener);
            } else if (!impediments.isEmpty() && peerGroup != null) {
                log.info("stopping peergroup");
                peerGroup.removeDisconnectedEventListener(peerConnectivityListener);
                peerGroup.removeConnectedEventListener(peerConnectivityListener);
                peerGroup.removeWallet(wallet);
                peerGroup.stopAsync();
                peerGroup = null;

                log.debug("releasing wakelock");
                wakeLock.release();
            }
        }
    };

    private final static class ActivityHistoryEntry {
        public final int numTransactionsReceived;
        public final int numBlocksDownloaded;

        public ActivityHistoryEntry(final int numTransactionsReceived, final int numBlocksDownloaded) {
            this.numTransactionsReceived = numTransactionsReceived;
            this.numBlocksDownloaded = numBlocksDownloaded;
        }

        @Override
        public String toString() {
            return numTransactionsReceived + "/" + numBlocksDownloaded;
        }
    }

    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        private int lastChainHeight = 0;
        private final List<ActivityHistoryEntry> activityHistory = new LinkedList<ActivityHistoryEntry>();

        @Override
        public void onReceive(final Context context, final Intent intent) {
            final int chainHeight = blockChain.getBestChainHeight();

            if (lastChainHeight > 0) {
                final int numBlocksDownloaded = chainHeight - lastChainHeight;
                final int numTransactionsReceived = transactionsReceived.getAndSet(0);

                // push history
                activityHistory.add(0, new ActivityHistoryEntry(numTransactionsReceived, numBlocksDownloaded));

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
                log.info("History of transactions/blocks: " + builder);

                // determine if block and transaction activity is idling
                boolean isIdle = false;
                if (activityHistory.size() >= MIN_COLLECT_HISTORY) {
                    isIdle = true;
                    for (int i = 0; i < activityHistory.size(); i++) {
                        final ActivityHistoryEntry entry = activityHistory.get(i);
                        final boolean blocksActive = entry.numBlocksDownloaded > 0 && i <= IDLE_BLOCK_TIMEOUT_MIN;
                        final boolean transactionsActive = entry.numTransactionsReceived > 0
                                && i <= IDLE_TRANSACTION_TIMEOUT_MIN;

                        if (blocksActive || transactionsActive) {
                            isIdle = false;
                            break;
                        }
                    }
                }

                // if idling, shutdown service
                if (isIdle) {
                    log.info("idling detected, stopping service");
                    stopSelf();
                }
            }

            lastChainHeight = chainHeight;
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

        application = (WalletApplication) getApplication();

        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        final String lockName = getPackageName() + " blockchain sync";

        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground();
        }

        config = application.getConfiguration();
        final Wallet wallet = application.getWallet();

        peerConnectivityListener = new PeerConnectivityListener();

        broadcastPeerState(0);

        blockChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME);
        final boolean blockChainFileExists = blockChainFile.exists();

        try {
            bootStrapStream = getAssets().open(Constants.Files.MNLIST_BOOTSTRAP_FILENAME);
            SimplifiedMasternodeListManager.setBootStrapStream(bootStrapStream);
        } catch (IOException x) {
            log.info("cannot load the boot strap stream.  " + x.getMessage());
        }

        if (!blockChainFileExists) {
            log.info("blockchain does not exist, resetting wallet");
            wallet.reset();
            try {
                SimplifiedMasternodeListManager manager = wallet.getContext().masternodeListManager;
                if (manager != null)
                    manager.resetMNList(true, true);
            } catch (RuntimeException x) {
                // swallow this exception.  It is thrown when there is not a bootstrap mnlist file
                // there is not a bootstrap mnlist file for testnet
            }
        }

        try {
            blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
            blockStore.getChainHead(); // detect corruptions as early as possible

            final long earliestKeyCreationTime = wallet.getEarliestKeyCreationTime();

            if (!blockChainFileExists && earliestKeyCreationTime > 0) {
                try {
                    final Stopwatch watch = Stopwatch.createStarted();
                    final InputStream checkpointsInputStream = getAssets().open(Constants.Files.CHECKPOINTS_FILENAME);
                    CheckpointManager.checkpoint(Constants.NETWORK_PARAMETERS, checkpointsInputStream, blockStore,
                            earliestKeyCreationTime);
                    watch.stop();
                    log.info("checkpoints loaded from '{}', took {}", Constants.Files.CHECKPOINTS_FILENAME, watch);
                } catch (final IOException x) {
                    log.error("problem reading checkpoints, continuing without", x);
                }
            }
        } catch (final BlockStoreException x) {
            blockChainFile.delete();
            SimplifiedMasternodeListManager manager = application.getWallet().getContext().masternodeListManager;
            if(manager != null) {
                manager.resetMNList(true, true);
            }

            final String msg = "blockstore cannot be created";
            log.error(msg, x);
            throw new Error(msg, x);
        }

        try {
            blockChain = new BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore);
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

        registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));

        wallet.getContext().initDashSync(getDir("masternode", MODE_PRIVATE).getAbsolutePath());

        peerDiscoveryList.add(dnsDiscovery);
        updateAppWidget();
        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(this, new Observer<BlockchainState>() {
            @Override
            public void onChanged(BlockchainState blockchainState) {
                handleBlockchainStateNotification(blockchainState);
            }
        });
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

                    peerGroup.broadcastTransaction(tx, minimum);
                } else {
                    log.info("peergroup not available, not broadcasting transaction " + tx.getHashAsString());
                    tx.getConfidence().setPeerInfo(0, 1);
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
    }

    @Override
    public void onDestroy() {
        log.debug(".onDestroy()");

        WalletApplication.scheduleStartBlockchainService(this);  //disconnect feature

        unregisterReceiver(tickReceiver);

        application.getWallet().removeChangeEventListener(walletEventListener);
        application.getWallet().removeCoinsSentEventListener(walletEventListener);
        application.getWallet().removeCoinsReceivedEventListener(walletEventListener);

        unregisterReceiver(connectivityReceiver);

        if (peerGroup != null) {
            peerGroup.removeDisconnectedEventListener(peerConnectivityListener);
            peerGroup.removeConnectedEventListener(peerConnectivityListener);
            peerGroup.removeWallet(application.getWallet());
            peerGroup.stop();

            log.info("peergroup stopped");
        }

        peerConnectivityListener.stop();

        delayHandler.removeCallbacksAndMessages(null);

        try {
            blockStore.close();
        } catch (final BlockStoreException x) {
            throw new RuntimeException(x);
        }

        if (!deleteWalletFileOnShutdown) {
            application.saveWallet();
        }

        //Dash Specific

        //Constants.NETWORK_PARAMETERS.masternodeDB.write(Constants.NETWORK_PARAMETERS.masternodeManager);
        //application.saveMasternodes();

        //Dash Specific

        if (wakeLock.isHeld()) {
            log.debug("wakelock still held, releasing");
            wakeLock.release();
        }

        if (resetBlockchainOnShutdown || deleteWalletFileOnShutdown) {
            log.info("removing blockchain");
            //noinspection ResultOfMethodCallIgnored
            blockChainFile.delete();
            SimplifiedMasternodeListManager manager = application.getWallet().getContext().masternodeListManager;
            if(manager != null) {
                manager.resetMNList(true, false);
            }
            if (deleteWalletFileOnShutdown) {
                log.info("removing wallet file and app data");
                application.finalizeWipe();
            }
        }

        if(bootStrapStream != null) {
            try {
                bootStrapStream.close();
            } catch (IOException x) {
                //do nothing
            }
        }

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
                notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        final String message = (blockchainState != null)
                ? BlockchainStateUtils.getSyncStateString(blockchainState, this)
                : getString(R.string.blockchain_state_progress_downloading);

        return new NotificationCompat.Builder(this,
                Constants.NOTIFICATION_CHANNEL_ID_ONGOING)
                .setSmallIcon(R.drawable.ic_dash_d_white_bottom)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setContentIntent(pendingIntent).build();
    }

    private void updateBlockchainStateImpediments() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                BlockchainStateDao dao = AppDatabase.getAppDatabase().blockchainStateDao();
                BlockchainState blockchainState = dao.loadSync();
                if (blockchainState != null) {
                    blockchainState.getImpediments().clear();
                    blockchainState.getImpediments().addAll(impediments);
                    dao.save(blockchainState);
                }
            }
        });
    }

    private void updateBlockchainState() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                BlockchainStateDao blockchainStateDao = AppDatabase.getAppDatabase().blockchainStateDao();
                BlockchainState blockchainState = blockchainStateDao.loadSync();
                if (blockchainState == null) {
                    blockchainState = new BlockchainState();
                }

                StoredBlock chainHead = blockChain.getChainHead();
                StoredBlock block = application.getWallet().getContext().chainLockHandler.getBestChainLockBlock();
                int chainLockHeight = block != null ? block.getHeight() : 0;
                int mnListHeight = (int) application.getWallet().getContext().masternodeListManager.getListAtChainTip().getHeight();

                blockchainState.setBestChainDate(chainHead.getHeader().getTime());
                blockchainState.setBestChainHeight(chainHead.getHeight());
                blockchainState.setImpediments(EnumSet.copyOf(impediments));
                blockchainState.setChainlockHeight(chainLockHeight);
                blockchainState.setMnlistHeight(mnListHeight);
                blockchainState.setPercentageSync(percentageSync());

                AppDatabase.getAppDatabase().blockchainStateDao().save(blockchainState);
            }
        });
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

    private void handleBlockchainStateNotification(BlockchainState blockchainState) {
        // send this out for the Network Monitor, other activities observe the database
        final Intent broadcast = new Intent(ACTION_BLOCKCHAIN_STATE);
        broadcast.setPackage(getPackageName());
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && blockchainState != null
                && blockchainState.getBestChainDate() != null) {
            //Handle Ongoing notification state
            boolean syncing = blockchainState.getBestChainDate().getTime() < (Utils.currentTimeMillis() - DateUtils.HOUR_IN_MILLIS); //1 hour
            if (!syncing && blockchainState.getBestChainHeight() == config.getBestChainHeightEver()) {
                //Remove ongoing notification if blockchain sync finished
                stopForeground(true);
                nm.cancel(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC);
            } else if (blockchainState.getReplaying() || syncing) {
                //Shows ongoing notification when synchronizing the blockchain
                Notification notification = createNetworkSyncNotification(blockchainState);
                nm.notify(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification);
            }
        }
    }

    private int percentageSync() {
        return syncPercentage;
    }

    private void updateAppWidget() {
        WalletBalanceWidgetProvider.updateWidgets(BlockchainServiceImpl.this, application.getWallet());
    }

    public void forceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(this, BlockchainServiceImpl.class);
            ContextCompat.startForegroundService(this, intent);
            // call startForeground just after startForegroundService.
            startForeground();
        }
    }
}
