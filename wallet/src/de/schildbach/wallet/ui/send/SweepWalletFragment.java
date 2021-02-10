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

package de.schildbach.wallet.ui.send;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;

import com.google.common.collect.ComparisonChain;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PrefixedChecksummedBytes;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.bitcoinj.wallet.WalletTransaction;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.dash.wallet.common.ui.DialogBuilder;
import org.dash.wallet.common.ui.FancyAlertDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.DynamicFeeLoader;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.rates.ExchangeRate;
import de.schildbach.wallet.rates.ExchangeRatesViewModel;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.TransactionResultActivity;
import de.schildbach.wallet.ui.scan.ScanActivity;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

import static com.google.common.base.Preconditions.checkState;

/**
 * @author Andreas Schildbach
 */
public class SweepWalletFragment extends Fragment {
    private SweepWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private LoaderManager loaderManager;
    private FragmentManager fragmentManager;

    private final Handler handler = new Handler();
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private State state = State.INTRO;
    private PrefixedChecksummedBytes privateKeyToSweep = null;
    @Nullable
    private Map<FeeCategory, Coin> fees = null;
    private Wallet walletToSweep = null;
    private Transaction sentTransaction = null;

    private View introductionGroup;
    private View balanceGroup;
    private CurrencyTextView balanceView;
    private Dialog decryptDialog;
    private Button viewGo;
    private ExchangeRate currentExchangeRate;

    private String password = "";

    private static final int ID_DYNAMIC_FEES_LOADER = 0;

    private static final int REQUEST_CODE_SCAN = 0;

    private FancyAlertDialog loadingDialog;

    private enum State {
        INTRO,
        DECODE_KEY, // ask for password
        CONFIRM_SWEEP, // displays balance and asks for confirmation
        PREPARATION, SENDING, SENT, FAILED // sending states
    }

    private static final Logger log = LoggerFactory.getLogger(SweepWalletFragment.class);

    private final LoaderManager.LoaderCallbacks<Map<FeeCategory, Coin>> dynamicFeesLoaderCallbacks = new LoaderManager.LoaderCallbacks<Map<FeeCategory, Coin>>() {
        @Override
        public Loader<Map<FeeCategory, Coin>> onCreateLoader(final int id, final Bundle args) {
            return new DynamicFeeLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<Map<FeeCategory, Coin>> loader, final Map<FeeCategory, Coin> data) {
            fees = data;
            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<Map<FeeCategory, Coin>> loader) {
        }
    };

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (SweepWalletActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.loaderManager = getLoaderManager();
        this.fragmentManager = getFragmentManager();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
        setHasOptionsMenu(true);

        backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        } else {
            final Intent intent = activity.getIntent();

            if (intent.hasExtra(SweepWalletActivity.INTENT_EXTRA_KEY)) {
                privateKeyToSweep = (PrefixedChecksummedBytes) intent
                        .getSerializableExtra(SweepWalletActivity.INTENT_EXTRA_KEY);

                // delay until fragment is resumed
                if (State.INTRO == state) {
                    state = State.DECODE_KEY;
                }
                handler.post(maybeDecodeKeyRunnable);
            }
        }
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.sweep_wallet_fragment, container);

        introductionGroup = view.findViewById(R.id.sweep_wallet_introduction_group);
        balanceGroup = view.findViewById(R.id.sweep_wallet_balance_group);

        balanceView = view.findViewById(R.id.sweep_wallet_fragment_balance);

        viewGo = view.findViewById(R.id.confirm_button);
        viewGo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                switch (state) {
                    case INTRO:
                        handleScan();
                        break;
                    case DECODE_KEY:
                        handleDecrypt();
                        break;
                    case CONFIRM_SWEEP:
                        handleSweep();
                        break;
                }
            }
        });

        ExchangeRatesViewModel exchangeRatesViewModel = new ViewModelProvider(this)
                .get(ExchangeRatesViewModel.class);
        exchangeRatesViewModel.getRate(config.getExchangeCurrencyCode()).observe(getViewLifecycleOwner(),
                exchangeRate -> {
                    if (exchangeRate != null) {
                        currentExchangeRate = exchangeRate;
                    }
                });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        loaderManager.initLoader(ID_DYNAMIC_FEES_LOADER, null, dynamicFeesLoaderCallbacks);

        updateView();
    }

    @Override
    public void onPause() {
        loaderManager.destroyLoader(ID_DYNAMIC_FEES_LOADER);

        super.onPause();
    }

    @Override
    public void onDestroy() {
        backgroundThread.getLooper().quit();
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        saveInstanceState(outState);
    }

    private void saveInstanceState(final Bundle outState) {
        outState.putSerializable("state", state);
        if (walletToSweep != null)
            outState.putByteArray("wallet_to_sweep", WalletUtils.walletToByteArray(walletToSweep));
        if (sentTransaction != null)
            outState.putSerializable("sent_transaction_hash", sentTransaction.getHash());
    }

    private void restoreInstanceState(final Bundle savedInstanceState) {
        state = (State) savedInstanceState.getSerializable("state");
        if (savedInstanceState.containsKey("wallet_to_sweep"))
            walletToSweep = WalletUtils.walletFromByteArray(savedInstanceState.getByteArray("wallet_to_sweep"));
        if (savedInstanceState.containsKey("sent_transaction_hash")) {
            sentTransaction = application.getWallet()
                    .getTransaction((Sha256Hash) savedInstanceState.getSerializable("sent_transaction_hash"));
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_SCAN) {
            if (resultCode == Activity.RESULT_OK) {
                final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);

                new StringInputParser(input, false) {
                    @Override
                    protected void handlePrivateKey(final PrefixedChecksummedBytes key) {
                        privateKeyToSweep = key;
                        setState(State.DECODE_KEY);
                        maybeDecodeKey();
                    }

                    @Override
                    protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                        cannotClassify(input);
                    }

                    @Override
                    protected void handleDirectTransaction(final Transaction transaction) throws VerificationException {
                        cannotClassify(input);
                    }

                    @Override
                    protected void error(Exception x, final int messageResId, final Object... messageArgs) {
                        dialog(activity, null, R.string.button_scan, messageResId, messageArgs);
                    }
                }.parse();
            }
        }
    }

    private void showProgress(int messageResId) {
        if (loadingDialog != null && loadingDialog.isAdded()) {
            loadingDialog.dismissAllowingStateLoss();
        }
        loadingDialog = FancyAlertDialog.newProgress(messageResId, 0);
        loadingDialog.show(getParentFragmentManager(), "progress");
    }

    private void dismissProgress() {
        if (loadingDialog != null && loadingDialog.isAdded()) {
            loadingDialog.dismissAllowingStateLoss();
        }
    }

    private void handleScan() {
        startActivityForResult(new Intent(activity, ScanActivity.class), REQUEST_CODE_SCAN);
    }

    private final Runnable maybeDecodeKeyRunnable = new Runnable() {
        @Override
        public void run() {
            maybeDecodeKey();
        }
    };

    private void maybeDecodeKey() {
        checkState(state == State.DECODE_KEY);
        checkState(privateKeyToSweep != null);

        if (privateKeyToSweep instanceof DumpedPrivateKey) {
            final ECKey key = ((DumpedPrivateKey) privateKeyToSweep).getKey();
            askConfirmSweep(key);
        } else if (privateKeyToSweep instanceof BIP38PrivateKey) {
            if (!password.isEmpty()) {
                showProgress(R.string.sweep_wallet_fragment_decrypt_progress);

                new DecodePrivateKeyTask(backgroundHandler) {
                    @Override
                    protected void onSuccess(ECKey decryptedKey) {
                        log.info("successfully decoded BIP38 private key");

                        dismissProgress();

                        askConfirmSweep(decryptedKey);
                    }

                    @Override
                    protected void onBadPassphrase() {
                        log.info("failed decoding BIP38 private key (bad password)");

                        dismissProgress();
                        showDecryptDialog(true);
                    }
                }.decodePrivateKey((BIP38PrivateKey) privateKeyToSweep, password);
            }
        } else {
            throw new IllegalStateException("cannot handle type: " + privateKeyToSweep.getClass().getName());
        }
    }

    private void askConfirmSweep(final ECKey key) {
        // create non-HD wallet
        final KeyChainGroup group = KeyChainGroup.builder(Constants.NETWORK_PARAMETERS).build();
        group.importKeys(key);
        walletToSweep = new Wallet(Constants.NETWORK_PARAMETERS, group);

        setState(State.CONFIRM_SWEEP);

        // delay until fragment is resumed
        handler.post(requestWalletBalanceRunnable);
    }

    private final Runnable requestWalletBalanceRunnable = new Runnable() {
        @Override
        public void run() {
            requestWalletBalance();
        }
    };

    private static final Comparator<UTXO> UTXO_COMPARATOR = new Comparator<UTXO>() {
        @Override
        public int compare(final UTXO lhs, final UTXO rhs) {
            return ComparisonChain.start().compare(lhs.getHash(), rhs.getHash()).compare(lhs.getIndex(), rhs.getIndex())
                    .result();
        }
    };

    private void requestWalletBalance() {
        showProgress(R.string.sweep_wallet_fragment_request_wallet_balance_progress);

        final RequestWalletBalanceTask.ResultCallback callback = new RequestWalletBalanceTask.ResultCallback() {
            @Override
            public void onResult(final Set<UTXO> utxos) {
                dismissProgress();

                // Filter UTXOs we've already spent and sort the rest.
                final Set<Transaction> walletTxns = application.getWallet().getTransactions(false);
                final Set<UTXO> sortedUtxos = new TreeSet<>(UTXO_COMPARATOR);
                for (final UTXO utxo : utxos)
                    if (!utxoSpentBy(walletTxns, utxo))
                        sortedUtxos.add(utxo);

                // Fake transaction funding the wallet to sweep.
                final Map<Sha256Hash, Transaction> fakeTxns = new HashMap<>();
                for (final UTXO utxo : sortedUtxos) {
                    Transaction fakeTx = fakeTxns.get(utxo.getHash());
                    if (fakeTx == null) {
                        fakeTx = new FakeTransaction(Constants.NETWORK_PARAMETERS, utxo.getHash());
                        fakeTx.getConfidence().setConfidenceType(ConfidenceType.BUILDING);
                        fakeTxns.put(fakeTx.getTxId(), fakeTx);
                    }
                    final TransactionOutput fakeOutput = new TransactionOutput(Constants.NETWORK_PARAMETERS, fakeTx,
                            utxo.getValue(), utxo.getScript().getProgram());
                    // Fill with output dummies as needed.
                    while (fakeTx.getOutputs().size() < utxo.getIndex())
                        fakeTx.addOutput(new TransactionOutput(Constants.NETWORK_PARAMETERS, fakeTx,
                                Coin.NEGATIVE_SATOSHI, new byte[]{}));
                    // Add the actual output we will spend later.
                    fakeTx.addOutput(fakeOutput);
                }

                walletToSweep.clearTransactions(0);
                for (final Transaction tx : fakeTxns.values())
                    walletToSweep.addWalletTransaction(new WalletTransaction(WalletTransaction.Pool.UNSPENT, tx));
                log.info("built wallet to sweep:\n{}", walletToSweep.toString(false, true, false, null));

                updateView();
            }

            private boolean utxoSpentBy(final Set<Transaction> transactions, final UTXO utxo) {
                for (final Transaction tx : transactions) {
                    for (final TransactionInput input : tx.getInputs()) {
                        final TransactionOutPoint outpoint = input.getOutpoint();
                        if (outpoint.getHash().equals(utxo.getHash()) && outpoint.getIndex() == utxo.getIndex())
                            return true;
                    }
                }
                return false;
            }

            @Override
            public void onFail(final int messageResId, final Object... messageArgs) {
                dismissProgress();

                final DialogBuilder dialog = DialogBuilder.warn(activity,
                        R.string.sweep_wallet_fragment_request_wallet_balance_failed_title);
                dialog.setMessage(activity.getString(messageResId, messageArgs));
                dialog.setPositiveButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        requestWalletBalance();
                    }
                });
                dialog.setNegativeButton(R.string.button_dismiss, null);
                dialog.show();
            }
        };

        final Address address = Address.fromKey(Constants.NETWORK_PARAMETERS, walletToSweep.getImportedKeys().iterator().next());
        new RequestWalletBalanceTask(backgroundHandler, callback).requestWalletBalance(activity.getAssets(), address);
    }

    private void setState(final State state) {
        this.state = state;

        updateView();
    }

    private void updateView() {
        final MonetaryFormat btcFormat = config.getFormat();

        if (walletToSweep != null) {
            introductionGroup.setVisibility(View.GONE);
            balanceGroup.setVisibility(View.VISIBLE);
            balanceView.setFormat(btcFormat.noCode());
            balanceView.setAmount(walletToSweep.getBalance(BalanceType.ESTIMATED));
        } else {
            introductionGroup.setVisibility(View.VISIBLE);
            balanceGroup.setVisibility(View.GONE);
        }

        if (state == State.DECODE_KEY && privateKeyToSweep != null) {
            showDecryptDialog();
        } else if (decryptDialog != null && decryptDialog.isShowing()) {
            decryptDialog.cancel();
            decryptDialog = null;
        }

        if (state == State.DECODE_KEY) {
            viewGo.setText(R.string.sweep_wallet_fragment_button_decrypt);
            viewGo.setEnabled(privateKeyToSweep != null);
        } else if (state == State.CONFIRM_SWEEP) {
            viewGo.setText(R.string.sweep_wallet_fragment_button_sweep);
            viewGo.setEnabled(walletToSweep != null && walletToSweep.getBalance(BalanceType.ESTIMATED).signum() > 0
                    && fees != null);
            log.info("State:  Confirm Sweep:  walletToSweep: " + (walletToSweep == null ? "valid" : "invalid") +
                    "wallet balance > 0: " + (walletToSweep.getBalance(BalanceType.ESTIMATED).signum() > 0 ? "true" : "false") +
                    "fees: " + (fees == null ? "null" : fees.toString()));
        } else if (state == State.PREPARATION) {
            viewGo.setText(R.string.send_coins_preparation_msg);
            viewGo.setEnabled(false);
        } else if (state == State.SENDING) {
            viewGo.setText(R.string.send_coins_sending_msg);
            viewGo.setEnabled(false);
        } else if (state == State.SENT) {
            viewGo.setText(R.string.send_coins_sent_msg);
            viewGo.setEnabled(false);
        } else if (state == State.FAILED) {
            viewGo.setText(R.string.send_coins_failed_msg);
            viewGo.setEnabled(false);
        }
    }

    private void showDecryptDialog() {
        showDecryptDialog(false);
    }

    private void showDecryptDialog(boolean badPassword) {
        if (!isAdded()) {
            return;
        }

        DialogBuilder dialogBuilder = new DialogBuilder(getActivity());
        dialogBuilder.setTitle(R.string.sweep_wallet_fragment_encrypted);

        View contentView = LayoutInflater.from(getActivity())
                .inflate(R.layout.sweep_wallet_decrypt_dialog, null);
        final TextView passwordText = contentView.findViewById(R.id.sweep_wallet_fragment_password);
        View badPasswordView = contentView.findViewById(R.id.sweep_wallet_fragment_bad_password);

        badPasswordView.setVisibility(badPassword ? View.VISIBLE : View.GONE);

        dialogBuilder.setView(contentView);
        dialogBuilder.setPositiveButton(R.string.sweep_wallet_fragment_button_decrypt, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                password = passwordText.getText().toString();
                handleDecrypt();
            }
        });
        dialogBuilder.setCancelable(false);

        if (decryptDialog != null) {
            decryptDialog.cancel();
            decryptDialog = null;
        }

        decryptDialog = dialogBuilder.show();
    }

    private void handleDecrypt() {
        handler.post(maybeDecodeKeyRunnable);
    }

    private void handleSweep() {
        setState(State.PREPARATION);

        final Address receivingAddress = application.getWallet().freshReceiveAddress();
        final SendRequest sendRequest = SendRequest.emptyWallet(receivingAddress);

        sendRequest.feePerKb = fees.get(FeeCategory.ECONOMIC);

        if (currentExchangeRate != null) {
            sendRequest.exchangeRate = new org.bitcoinj.utils.ExchangeRate(
                    Coin.COIN, currentExchangeRate.getFiat());
            log.info("Using exchange rate: " + sendRequest.exchangeRate.coinToFiat(Coin.COIN).toFriendlyString());
        }

        new SendCoinsOfflineTask(walletToSweep, backgroundHandler) {
            @Override
            protected void onSuccess(final Transaction transaction) {
                sentTransaction = transaction;

                setState(State.SENDING);
                application.processDirectTransaction(sentTransaction);
                showTransactionResult(sentTransaction);
            }

            @Override
            protected void onInsufficientMoney(@Nullable final Coin missing) {
                setState(State.FAILED);

                showInsufficientMoneyDialog();
            }

            @Override
            protected void onEmptyWalletFailed() {
                setState(State.FAILED);

                showInsufficientMoneyDialog();
            }

            @Override
            protected void onFailure(final Exception exception) {
                setState(State.FAILED);

                final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_error_msg);
                dialog.setMessage(exception.toString());
                dialog.setNeutralButton(R.string.button_dismiss, null);
                dialog.show();
            }

            @Override
            protected void onInvalidEncryptionKey() {
                throw new RuntimeException(); // cannot happen
            }

            private void showInsufficientMoneyDialog() {
                final DialogBuilder dialog = DialogBuilder.warn(activity,
                        R.string.sweep_wallet_fragment_insufficient_money_title);
                dialog.setMessage(R.string.sweep_wallet_fragment_insufficient_money_msg);
                dialog.setNeutralButton(R.string.button_dismiss, null);
                dialog.show();
            }
        }.sendCoinsOffline(sendRequest); // send asynchronously
    }

    private void showTransactionResult(Transaction sentTransaction) {
        if (!isAdded()) {
            return;
        }

        Intent transactionResultIntent = TransactionResultActivity.createIntent(activity,
                null, sentTransaction, activity.isUserAuthorized());
        startActivity(transactionResultIntent);
        activity.finish();
    }

    private static class FakeTransaction extends Transaction {
        private final Sha256Hash txId;

        public FakeTransaction(final NetworkParameters params, final Sha256Hash txId) {
            super(params);
            this.txId = txId;
        }

        @Override
        public Sha256Hash getTxId() {
            return txId;
        }
    }
}
