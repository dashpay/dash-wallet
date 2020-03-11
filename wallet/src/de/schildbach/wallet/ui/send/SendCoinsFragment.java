/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.send;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.RingtoneManager;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;

import org.bitcoin.protocols.payments.Protos.Payment;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.PrefixedChecksummedBytes;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.KeyChain.KeyPurpose;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.bitcoinj.wallet.Wallet.CouldNotAdjustDownwards;
import org.bitcoinj.wallet.Wallet.DustySendRequested;
import org.bitcoinj.wallet.ZeroConfCoinSelector;
import org.bouncycastle.crypto.params.KeyParameter;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.DialogBuilder;
import org.dash.wallet.common.util.GenericUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Objects;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.data.PaymentIntent.Standard;
import de.schildbach.wallet.integration.android.BitcoinIntegration;
import de.schildbach.wallet.ui.CheckPinDialog;
import de.schildbach.wallet.ui.CheckPinSharedModel;
import de.schildbach.wallet.ui.InputParser;
import de.schildbach.wallet.ui.SingleActionSharedViewModel;
import de.schildbach.wallet.ui.TransactionResultActivity;
import de.schildbach.wallet.ui.security.SecurityGuard;
import de.schildbach.wallet_test.R;

public class SendCoinsFragment extends Fragment {

    private static Coin ECONOMIC_FEE = Coin.valueOf(1000);

    protected WalletApplication application;
    protected SendCoinsActivity activity;
    protected FragmentManager fragmentManager;
    private Configuration config;

    protected final Handler handler = new Handler();
    private HandlerThread backgroundThread;
    protected Handler backgroundHandler;

    private static final int AUTH_REQUEST_CODE_MAX = 1;
    private static final int AUTH_REQUEST_CODE_SEND = 2;

    private static final Logger log = LoggerFactory.getLogger(SendCoinsFragment.class);

    private SendCoinsViewModel viewModel;
    private EnterAmountSharedViewModel enterAmountSharedViewModel;

    private boolean wasAmountChangedByTheUser = false;

    private boolean userAuthorizedDuring = false;

    private SecurityGuard securityGuard;
    private BlockchainState blockchainState;

    private boolean handlePaymentRequest = false;

    private boolean isUserAuthorized() {
        return activity.isUserAuthorized() || userAuthorizedDuring;
    }

    final DialogInterface.OnClickListener activityDismissListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(final DialogInterface dialog, final int which) {
            activity.finish();
        }
    };

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);

        this.activity = (SendCoinsActivity) context;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.fragmentManager = getFragmentManager();
    }

    @Override
    public void onActivityCreated(@androidx.annotation.Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        try {
            securityGuard = new SecurityGuard();
        } catch (Exception e) {
            log.error("Unable to instantiate SecurityGuard", e);
            activity.finish();
            return;
        }

        viewModel = ViewModelProviders.of(this).get(SendCoinsViewModel.class);

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(this, new Observer<BlockchainState>() {
            @Override
            public void onChanged(BlockchainState blockchainState) {
                SendCoinsFragment.this.blockchainState = blockchainState;
                updateView();
            }
        });

        CheckPinSharedModel checkPinSharedModel = ViewModelProviders.of(activity).get(CheckPinSharedModel.class);
        checkPinSharedModel.getOnCorrectPinCallback().observe(activity, new Observer<kotlin.Pair<Integer, String>>() {
            @Override
            public void onChanged(kotlin.Pair<Integer, String> data) {
                userAuthorizedDuring = true;
                switch (data.getFirst()) {
                    case AUTH_REQUEST_CODE_MAX:
                        handleEmpty();
                        break;
                    case AUTH_REQUEST_CODE_SEND:
                        if (everythingPlausible() && viewModel.dryrunSendRequest != null) {
                            showPaymentConfirmation();
                        } else {
                            updateView();
                        }
                        break;
                }
            }
        });

        enterAmountSharedViewModel = ViewModelProviders.of(activity).get(EnterAmountSharedViewModel.class);
        enterAmountSharedViewModel.getDashAmountData().observe(getViewLifecycleOwner(), new Observer<Coin>() {
            @Override
            public void onChanged(Coin amount) {
                if (!wasAmountChangedByTheUser) {
                    wasAmountChangedByTheUser = Coin.ZERO.isLessThan(amount);
                }
                handler.post(dryrunRunnable);
            }
        });
        enterAmountSharedViewModel.getButtonClickEvent().observe(getViewLifecycleOwner(), new Observer<Coin>() {
            @Override
            public void onChanged(Coin coin) {
                authenticateOrConfirm();
            }
        });
        enterAmountSharedViewModel.getMaxButtonClickEvent().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean unused) {
                if (isUserAuthorized()) {
                    handleEmpty();
                } else {
                    CheckPinDialog.show(activity, AUTH_REQUEST_CODE_MAX);
                }
            }
        });
        SingleActionSharedViewModel confirmTransactionSharedViewModel = ViewModelProviders.of(activity).get(SingleActionSharedViewModel.class);
        confirmTransactionSharedViewModel.getClickConfirmButtonEvent().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                handleGo();
            }
        });
        viewModel.state.observe(getViewLifecycleOwner(), new Observer<SendCoinsViewModel.State>() {
            @Override
            public void onChanged(SendCoinsViewModel.State state) {
                updateView();
            }
        });
        viewModel.onSendCoinsOffline.observe(getViewLifecycleOwner(), new Observer<Pair<SendCoinsViewModel.SendCoinsOfflineStatus, Object>>() {
            @Override
            public void onChanged(Pair<SendCoinsViewModel.SendCoinsOfflineStatus, Object> sendCoinsData) {

                SendCoinsViewModel.SendCoinsOfflineStatus error = sendCoinsData.first;
                switch (error) {
                    case SUCCESS: {
                        Transaction transaction = (Transaction) sendCoinsData.second;
                        onSignAndSendPaymentSuccess(transaction);
                        break;
                    }
                    case INSUFFICIENT_MONEY: {
                        Coin missing = (Coin) sendCoinsData.second;
                        showInsufficientMoneyDialog(missing);
                        break;
                    }
                    case INVALID_ENCRYPTION_KEY: {
                        break;
                    }
                    case EMPTY_WALLET_FAILED: {
                        showEmptyWalletFailedDialog();
                        break;
                    }
                    case FAILURE: {
                        Exception exception = (Exception) sendCoinsData.second;
                        showFailureDialog(exception);
                        break;
                    }
                }
            }
        });

        if (savedInstanceState == null) {
            final Intent intent = activity.getIntent();
            initiate(intent);
        }
    }

    void initiate(Intent intent) {
        final String action = intent.getAction();
        final Uri intentUri = intent.getData();
        final String scheme = intentUri != null ? intentUri.getScheme() : null;

        if ((Intent.ACTION_VIEW.equals(action) || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action))
                && intentUri != null && ("dash".equals(scheme) || "pay".equals(scheme))) {

            initStateFromDashUri(intentUri);

        } else if (intent.hasExtra(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT)) {

            initStateFromIntentExtras(intent.getExtras());

        } else {

            updateStateFrom(PaymentIntent.blank());
        }
    }

    void initStateFromIntentUri(final String mimeType, final Uri bitcoinUri) {
        try {
            final InputStream is = activity.getContentResolver().openInputStream(bitcoinUri);

            new InputParser.StreamInputParser(mimeType, is) {
                @Override
                protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                    updateStateFrom(paymentIntent);
                }

                @Override
                protected void error(final int messageResId, final Object... messageArgs) {
                    dialog(activity, activityDismissListener, 0, messageResId, messageArgs);
                }
            }.parse();
        } catch (final FileNotFoundException x) {
            throw new RuntimeException(x);
        }
    }

    private void initStateFromDashUri(final Uri dashUri) {
        final String input = dashUri.toString();

        new InputParser.StringInputParser(input) {
            @Override
            protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                updateStateFrom(paymentIntent);
            }

            @Override
            protected void handlePrivateKey(final PrefixedChecksummedBytes key) {
                throw new UnsupportedOperationException();
            }

            @Override
            protected void handleDirectTransaction(final Transaction transaction) throws VerificationException {
                throw new UnsupportedOperationException();
            }

            @Override
            protected void error(final int messageResId, final Object... messageArgs) {
                dialog(activity, activityDismissListener, 0, messageResId, messageArgs);
            }
        }.parse();
    }

    private void initStateFromIntentExtras(final Bundle extras) {
        final PaymentIntent paymentIntent = extras.getParcelable(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT);

        updateStateFrom(paymentIntent);
    }

    void updateStateFrom(final PaymentIntent paymentIntent) {
        log.info("got {}", paymentIntent);

        viewModel.paymentIntent = paymentIntent;
    }

    private void authenticateOrConfirm() {
        if (everythingPlausible()) {
            if (!isUserAuthorized() || config.getSpendingConfirmationEnabled()) {
                Coin thresholdAmount = Coin.parseCoin(
                        Float.valueOf(config.getBiometricLimit()).toString());
                if (enterAmountSharedViewModel.getDashAmount().isLessThan(thresholdAmount)) {
                    CheckPinDialog.show(activity, AUTH_REQUEST_CODE_SEND);
                } else {
                    CheckPinDialog.show(activity, AUTH_REQUEST_CODE_SEND, true);
                }
            } else if (viewModel.dryrunException == null) {
                showPaymentConfirmation();
            }
        }
        updateView();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    void specifyAmount(Coin amount) {
        enterAmountSharedViewModel.getChangeDashAmountEvent().setValue(amount);
    }

    PaymentIntent getPaymentIntent() {
        return viewModel.paymentIntent;
    }

    SendCoinsViewModel.State getState() {
        return viewModel.state.getValue();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.send_coins_fragment, container);
    }

    @Override
    public void onResume() {
        super.onResume();

        updateView();
        handler.post(dryrunRunnable);
    }

    @Override
    public void onDetach() {
        handler.removeCallbacksAndMessages(null);

        super.onDetach();
    }

    @Override
    public void onDestroy() {
        backgroundThread.getLooper().quit();
        super.onDestroy();
    }

    protected void handleCancel() {
        if (getState() == null || getState().compareTo(SendCoinsViewModel.State.INPUT) <= 0) {
            activity.setResult(Activity.RESULT_CANCELED);
        }

        activity.finish();
    }

    private boolean isPayeePlausible() {
        return viewModel.paymentIntent != null && viewModel.paymentIntent.hasOutputs();
    }

    private boolean isAmountPlausible() {
        if (viewModel.paymentIntent.mayEditAmount()) {
            return enterAmountSharedViewModel.hasAmount();
        } else {
            return viewModel.paymentIntent.hasAmount();
        }
    }

    private boolean everythingPlausible() {
        return getState() == SendCoinsViewModel.State.INPUT && isPayeePlausible() && isAmountPlausible();
    }

    private void handleGo() {
        final Wallet wallet = viewModel.wallet;
        if (wallet.isEncrypted()) {

            new DeriveKeyTask(backgroundHandler, application.scryptIterationsTarget()) {
                @Override
                protected void onSuccess(final KeyParameter encryptionKey, final boolean wasChanged) {
                    if (wasChanged)
                        application.backupWallet();
                    signAndSendPayment(encryptionKey);
                }
            }.deriveKey(wallet, securityGuard.retrievePassword());

            setState(SendCoinsViewModel.State.DECRYPTING);
        } else {
            signAndSendPayment(null);
        }
    }

    private void signAndSendPayment(final KeyParameter encryptionKey) {
        if (viewModel.dryrunSendRequest == null) {
            log.error("illegal state dryrunSendRequest == null");
            return;
        }

        // final payment intent
        Coin finalAmount = enterAmountSharedViewModel.getDashAmount();
        final PaymentIntent finalPaymentIntent = viewModel.paymentIntent.mergeWithEditedValues(finalAmount, null);
        boolean ensureMinRequiredFee = viewModel.dryrunSendRequest.ensureMinRequiredFee;

        SendRequest sendRequest = createSendRequest(finalPaymentIntent, true, ensureMinRequiredFee);
        sendRequest.memo = viewModel.paymentIntent.memo;
        sendRequest.exchangeRate = enterAmountSharedViewModel.getExchangeRate();
        sendRequest.aesKey = encryptionKey;

        viewModel.signAndSendPayment(sendRequest, backgroundHandler, finalPaymentIntent);
    }

    private void onSignAndSendPaymentSuccess(Transaction transaction) {
        final Wallet wallet = viewModel.wallet;
        viewModel.sentTransaction = Objects.requireNonNull(transaction);

        Coin finalAmount = viewModel.finalPaymentIntent.getAmount();

        final Address refundAddress = viewModel.paymentIntent.standard == Standard.BIP70
                ? wallet.freshAddress(KeyPurpose.REFUND) : null;
        final Payment payment = PaymentProtocol.createPaymentMessage(
                Collections.singletonList(viewModel.sentTransaction), finalAmount, refundAddress, null,
                viewModel.paymentIntent.payeeData);

        Intent resultIntent = new Intent();
        onSendCoinsOfflineTaskSuccess(payment, resultIntent);

        application.broadcastTransaction(viewModel.sentTransaction);

        final ComponentName callingActivity = activity.getCallingActivity();
        if (callingActivity != null) {
            log.info("returning result to calling activity: {}", callingActivity.flattenToString());
            BitcoinIntegration.transactionHashToResult(resultIntent, viewModel.sentTransaction.getTxId().toString());
            activity.setResult(Activity.RESULT_OK, resultIntent);
        }
        showTransactionResult(viewModel.sentTransaction, wallet);
        playSentSound();
        activity.finish();
    }

    private void showInsufficientMoneyDialog(Coin missing) {

        final Wallet wallet = viewModel.wallet;
        final Coin estimated = wallet.getBalance(BalanceType.ESTIMATED);
        final Coin available = wallet.getBalance(BalanceType.AVAILABLE);
        final Coin pending = estimated.subtract(available);

        final MonetaryFormat dashFormat = config.getFormat();

        final DialogBuilder dialog = DialogBuilder.warn(activity,
                R.string.send_coins_fragment_insufficient_money_title);
        final StringBuilder msg = new StringBuilder();
        msg.append(getString(R.string.send_coins_fragment_insufficient_money_msg1, dashFormat.format(missing)));

        if (pending.signum() > 0)
            msg.append("\n\n")
                    .append(getString(R.string.send_coins_fragment_pending, dashFormat.format(pending)));
        if (viewModel.paymentIntent.mayEditAmount())
            msg.append("\n\n").append(getString(R.string.send_coins_fragment_insufficient_money_msg2));
        dialog.setMessage(msg);
        if (viewModel.paymentIntent.mayEditAmount()) {
            dialog.setPositiveButton(R.string.send_coins_options_empty, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int which) {
                    handleEmpty();
                }
            });
            dialog.setNegativeButton(R.string.button_cancel, null);
        } else {
            dialog.setNeutralButton(R.string.button_dismiss, null);
        }
        dialog.show();
    }

    private void showEmptyWalletFailedDialog() {
        final DialogBuilder dialog = DialogBuilder.warn(activity,
                R.string.send_coins_fragment_empty_wallet_failed_title);
        dialog.setMessage(R.string.send_coins_fragment_hint_empty_wallet_failed);
        dialog.setNeutralButton(R.string.button_dismiss, null);
        dialog.show();
    }

    protected void showFailureDialog(Exception exception) {
        final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_error_msg);
        dialog.setMessage(exception.toString());
        dialog.setNeutralButton(R.string.button_dismiss, null);
        dialog.show();
    }

    void onSendCoinsOfflineTaskSuccess(Payment payment, Intent resultIntent) {

    }

    private void showTransactionResult(Transaction transaction, Wallet wallet) {
        if (!isAdded()) {
            return;
        }

        Intent transactionResultIntent = TransactionResultActivity.createIntent(activity,
                transaction, activity.isUserAuthorized());
        startActivity(transactionResultIntent);
    }

    private void handleEmpty() {
        final Coin available = viewModel.wallet.getBalance(BalanceType.ESTIMATED);
        enterAmountSharedViewModel.getApplyMaxAmountEvent().setValue(available);

        updateView();
        handler.post(dryrunRunnable);
    }

    protected Runnable dryrunRunnable = new Runnable() {
        @Override
        public void run() {
            if (getState() == SendCoinsViewModel.State.INPUT)
                executeDryrun();

            updateView();
        }

        private void executeDryrun() {

            viewModel.dryrunSendRequest = null;
            viewModel.dryrunException = null;

            final Coin amount = enterAmountSharedViewModel.getDashAmount();
            final Wallet wallet = viewModel.wallet;
            final Address dummyAddress = wallet.currentReceiveAddress(); // won't be used, tx is never committed

            if (Coin.ZERO.equals(amount)) {
                return;
            }

            final PaymentIntent finalPaymentIntent = viewModel.paymentIntent.mergeWithEditedValues(amount, dummyAddress);

            try {
                // check regular payment
                SendRequest sendRequest = createSendRequest(finalPaymentIntent, false, false);

                wallet.completeTx(sendRequest);
                if (checkDust(sendRequest)) {
                    sendRequest = createSendRequest(finalPaymentIntent, false, true);
                    wallet.completeTx(sendRequest);
                }
                viewModel.dryrunSendRequest = sendRequest;
            } catch (final Exception x) {
                viewModel.dryrunException = x;
            }

            if (handlePaymentRequest) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handlePaymentRequest = false;
                        authenticateOrConfirm();
                    }
                });
            }
        }
    };

    private SendRequest createSendRequest(PaymentIntent paymentIntent, boolean signInputs, boolean forceEnsureMinRequiredFee) {

        paymentIntent.setInstantX(false); //to make sure the correct instance of Transaction class is used in toSendRequest() method
        final SendRequest sendRequest = paymentIntent.toSendRequest();
        sendRequest.coinSelector = ZeroConfCoinSelector.get();
        sendRequest.useInstantSend = false;
        sendRequest.feePerKb = ECONOMIC_FEE;
        sendRequest.ensureMinRequiredFee = forceEnsureMinRequiredFee;
        sendRequest.signInputs = signInputs;

        Coin walletBalance = viewModel.wallet.getBalance(BalanceType.ESTIMATED);
        sendRequest.emptyWallet = viewModel.paymentIntent.mayEditAmount() && walletBalance.equals(paymentIntent.getAmount());

        return sendRequest;
    }

    protected void setState(final SendCoinsViewModel.State state) {
        viewModel.state.setValue(state);
    }

    @SuppressLint("SetTextI18n")
    protected void updateView() {

        if (viewModel.paymentIntent == null) {
            log.info("viewModel.paymentIntent == null");
            return;
        }

        enterAmountSharedViewModel.getDirectionChangeEnabledData().setValue(
                getState() == SendCoinsViewModel.State.INPUT && viewModel.paymentIntent.mayEditAmount());

        enterAmountSharedViewModel.getMessageTextStringData().setValue(null);
        if (getState() == SendCoinsViewModel.State.INPUT) {
            CharSequence message = null;
            if (blockchainState != null && blockchainState.getReplaying()) {
                message = coloredString(getString(R.string.send_coins_fragment_hint_replaying), R.color.dash_red, true);
                enterAmountSharedViewModel.getMessageTextStringData().setValue(message);
            } else {
                if (Coin.ZERO.equals(enterAmountSharedViewModel.getDashAmount()) && wasAmountChangedByTheUser) {
                    message = coloredString(getString(R.string.send_coins_fragment_hint_dusty_send), R.color.dash_red, true);
                } else if (viewModel.dryrunException != null) {
                    if (viewModel.dryrunException instanceof DustySendRequested)
                        message = coloredString(getString(R.string.send_coins_fragment_hint_dusty_send), R.color.dash_red, true);
                    else if (viewModel.dryrunException instanceof InsufficientMoneyException) {
                        message = coloredString(getString(R.string.send_coins_fragment_hint_insufficient_money), R.color.dash_red, true);
                    } else if (viewModel.dryrunException instanceof CouldNotAdjustDownwards) {
                        message = coloredString(getString(R.string.send_coins_fragment_hint_dusty_send), R.color.dash_red, true);
                    } else {
                        message = coloredString(viewModel.dryrunException.toString(), R.color.dash_red, true);
                    }
                }
                if (isUserAuthorized()) {
                    enterAmountSharedViewModel.getMessageTextStringData().setValue(message);
                }
            }
        }

        enterAmountSharedViewModel.getButtonEnabledData().setValue(everythingPlausible()
                && (!isUserAuthorized() || viewModel.dryrunSendRequest != null)
                && (blockchainState == null || !blockchainState.getReplaying()));

        SendCoinsViewModel.State state = getState();
        if (state == null || state == SendCoinsViewModel.State.REQUEST_PAYMENT_REQUEST) {
            enterAmountSharedViewModel.getButtonTextData().call(0);
        } else if (state == SendCoinsViewModel.State.INPUT) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_fragment_button_send);
        } else if (state == SendCoinsViewModel.State.DECRYPTING) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_fragment_state_decrypting);
        } else if (state == SendCoinsViewModel.State.SIGNING) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_preparation_msg);
        } else if (state == SendCoinsViewModel.State.SENDING) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_sending_msg);
        } else if (state == SendCoinsViewModel.State.SENT) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_sent_msg);
        } else if (state == SendCoinsViewModel.State.FAILED) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_failed_msg);
        }
    }

    private Spannable coloredString(String text, int color, Boolean bold) {
        Spannable spannable = new SpannableString(text);
        ForegroundColorSpan colorSpan = new ForegroundColorSpan(getResources().getColor(color));
        spannable.setSpan(colorSpan, 0, spannable.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            StyleSpan styleSpan = new StyleSpan(Typeface.BOLD);
            spannable.setSpan(styleSpan, 0, spannable.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spannable;
    }

    private boolean checkDust(SendRequest req) {
        if (req.tx != null) {
            for (TransactionOutput output : req.tx.getOutputs()) {
                if (output.isDust())
                    return true;
            }
        }
        return false;
    }

    private void showPaymentConfirmation() {
        if (viewModel.dryrunSendRequest == null) {
            log.error("illegal state dryrunSendRequest == null");
            return;
        }

        Coin txFee = viewModel.dryrunSendRequest.tx.getFee();
        Coin amount;
        String total;
        if (viewModel.dryrunSendRequest.emptyWallet) {
            amount = enterAmountSharedViewModel.getDashAmount().minus(txFee);
            total = enterAmountSharedViewModel.getDashAmount().toPlainString();
        } else {
            amount = enterAmountSharedViewModel.getDashAmount();
            total = amount.add(txFee).toPlainString();
        }

        String address = viewModel.paymentIntent.getAddress().toBase58();
        ExchangeRate rate = enterAmountSharedViewModel.getExchangeRate();
        // prevent crash if the exchange rate is null
        Fiat fiatAmount = rate != null ? rate.coinToFiat(amount) : null;

        String amountStr = MonetaryFormat.BTC.noCode().format(amount).toString();
        // if the exchange rate is not available, then show "Not Available"
        String amountFiat = fiatAmount != null ? Constants.LOCAL_FORMAT.format(fiatAmount).toString() : getString(R.string.transaction_row_rate_not_available);
        String fiatSymbol = fiatAmount != null ? GenericUtils.currencySymbol(fiatAmount.currencyCode) : "";
        String fee = txFee.toPlainString();

        String payeeName = null;
        String payeeVerifiedBy = null;
        if (viewModel.paymentIntent.hasPayee()) {
            payeeName = viewModel.paymentIntent.payeeName;
            payeeVerifiedBy = viewModel.paymentIntent.payeeVerifiedBy != null ? viewModel.paymentIntent.payeeVerifiedBy : getString(R.string.send_coins_fragment_payee_verified_by_unknown);
        }

        DialogFragment dialog = ConfirmTransactionDialog.createDialog(address, amountStr, amountFiat,
                fiatSymbol, fee, total, payeeName, payeeVerifiedBy, null);
        dialog.show(Objects.requireNonNull(getFragmentManager()), "ConfirmTransactionDialog");
    }


    private void playSentSound() {
        // play sound effect
        final int soundResId = getResources().getIdentifier("send_coins_broadcast_1",
                "raw", activity.getPackageName());
        if (soundResId > 0)
            RingtoneManager
                    .getRingtone(activity, Uri.parse(
                            "android.resource://" + activity.getPackageName() + "/" + soundResId))
                    .play();
    }
}
