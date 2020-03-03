/*
 * Copyright 2019 Dash Core Group
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
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.RingtoneManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
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
import java.util.Arrays;

import javax.annotation.Nullable;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.data.PaymentIntent.Standard;
import de.schildbach.wallet.integration.android.BitcoinIntegration;
import de.schildbach.wallet.offline.DirectPaymentTask;
import de.schildbach.wallet.ui.CheckPinDialog;
import de.schildbach.wallet.ui.CheckPinSharedModel;
import de.schildbach.wallet.ui.InputParser.BinaryInputParser;
import de.schildbach.wallet.ui.InputParser.StreamInputParser;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.ProgressDialogFragment;
import de.schildbach.wallet.ui.SingleActionSharedViewModel;
import de.schildbach.wallet.ui.TransactionResultActivity;
import de.schildbach.wallet.ui.security.SecurityGuard;
import de.schildbach.wallet.util.Bluetooth;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet_test.R;
import kotlin.Pair;

import static org.dash.wallet.common.Constants.CHAR_CHECKMARK;

public final class SendCoinsFragment extends Fragment {

    private static Coin ECONOMIC_FEE = Coin.valueOf(1000);

    private SendCoinsActivity activity;
    private WalletApplication application;
    private Configuration config;
    private FragmentManager fragmentManager;
    @Nullable
    private BluetoothAdapter bluetoothAdapter;

    private final Handler handler = new Handler();
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private TextView payeeNameView;
    private TextView payeeVerifiedByView;
    private TextView receivingStaticAddressView;
    private CheckBox directPaymentEnableView;

    private TextView directPaymentMessageView;

    private static final int REQUEST_CODE_ENABLE_BLUETOOTH_FOR_PAYMENT_REQUEST = 0;
    private static final int REQUEST_CODE_ENABLE_BLUETOOTH_FOR_DIRECT_PAYMENT = 1;

    private static final int AUTH_REQUEST_CODE_MAX = 1;
    private static final int AUTH_REQUEST_CODE_SEND = 2;

    private static final Logger log = LoggerFactory.getLogger(SendCoinsFragment.class);

    private SendCoinsViewModel viewModel;
    private EnterAmountSharedViewModel enterAmountSharedViewModel;

    private boolean wasAmountChangedByTheUser = false;

    private boolean userAuthorizedDuring = false;

    private SecurityGuard securityGuard;
    private BlockchainState blockchainState;

    private boolean isUserAuthorized() {
        return activity.isUserAuthorized() || userAuthorizedDuring;
    }

    private final DialogInterface.OnClickListener activityDismissListener = new DialogInterface.OnClickListener() {
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
        checkPinSharedModel.getOnCorrectPinCallback().observe(activity, new Observer<Pair<Integer, String>>() {
            @Override
            public void onChanged(Pair<Integer, String> data) {
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
                if (everythingPlausible()) {
                    if (!isUserAuthorized() || config.getSpendingConfirmationEnabled()) {
                        Coin thresholdAmount = Coin.parseCoin(
                                Float.valueOf(config.getBiometricLimit()).toString());
                        if (enterAmountSharedViewModel.getDashAmount().isLessThan(thresholdAmount)) {
                            CheckPinDialog.show(activity, AUTH_REQUEST_CODE_SEND);
                        } else {
                            CheckPinDialog.show(activity, AUTH_REQUEST_CODE_SEND, true);
                        }
                    } else {
                        showPaymentConfirmation();
                    }
                }
                updateView();
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

        if (savedInstanceState == null) {
            final Intent intent = activity.getIntent();
            final String action = intent.getAction();
            final Uri intentUri = intent.getData();
            final String scheme = intentUri != null ? intentUri.getScheme() : null;
            final String mimeType = intent.getType();

            if ((Intent.ACTION_VIEW.equals(action) || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action))
                    && intentUri != null && "dash".equals(scheme)) {
                initStateFromDashUri(intentUri);
            } else if ((NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action))
                    && PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {
                final NdefMessage ndefMessage = (NdefMessage) intent
                        .getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
                final byte[] ndefMessagePayload = Nfc.extractMimePayload(PaymentProtocol.MIMETYPE_PAYMENTREQUEST,
                        ndefMessage);
                initStateFromPaymentRequest(mimeType, ndefMessagePayload);
            } else if ((Intent.ACTION_VIEW.equals(action))
                    && PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {
                final byte[] paymentRequest = BitcoinIntegration.paymentRequestFromIntent(intent);

                if (intentUri != null)
                    initStateFromIntentUri(mimeType, intentUri);
                else if (paymentRequest != null)
                    initStateFromPaymentRequest(mimeType, paymentRequest);
                else
                    throw new IllegalArgumentException();
            } else if (intent.hasExtra(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT)) {
                initStateFromIntentExtras(intent.getExtras());
            } else {
                updateStateFrom(PaymentIntent.blank());
            }
        }
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.send_coins_fragment, container);

        payeeNameView = view.findViewById(R.id.send_coins_payee_name);
        payeeVerifiedByView = view.findViewById(R.id.send_coins_payee_verified_by);

        receivingStaticAddressView = view.findViewById(R.id.send_coins_receiving_static_address);

        directPaymentEnableView = view.findViewById(R.id.send_coins_direct_payment_enable);
        directPaymentEnableView.setTypeface(ResourcesCompat.getFont(getActivity(), R.font.montserrat_medium));
        directPaymentEnableView.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
                if (viewModel.paymentIntent.isBluetoothPaymentUrl() && isChecked && !bluetoothAdapter.isEnabled()) {
                    // ask for permission to enable bluetooth
                    startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                            REQUEST_CODE_ENABLE_BLUETOOTH_FOR_DIRECT_PAYMENT);
                }
            }
        });

        directPaymentMessageView = view.findViewById(R.id.send_coins_direct_payment_message);

        return view;
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

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                onActivityResultResumed(requestCode, resultCode, intent);
            }
        });
    }

    private void onActivityResultResumed(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH_FOR_PAYMENT_REQUEST) {
            if (viewModel.paymentIntent.isBluetoothPaymentRequestUrl())
                requestPaymentRequest();
        } else if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH_FOR_DIRECT_PAYMENT) {
            if (viewModel.paymentIntent.isBluetoothPaymentUrl())
                directPaymentEnableView.setChecked(resultCode == Activity.RESULT_OK);
        }
    }

    private void handleCancel() {
        if (viewModel.state == null || viewModel.state.compareTo(SendCoinsViewModel.State.INPUT) <= 0) {
            activity.setResult(Activity.RESULT_CANCELED);
        }

        activity.finish();
    }

    private boolean isPayeePlausible() {
        return viewModel.paymentIntent != null && viewModel.paymentIntent.hasOutputs();
    }

    private boolean isAmountPlausible() {
        if (viewModel.dryrunSendRequest != null) {
            return viewModel.dryrunException == null;
        } else if (viewModel.paymentIntent.mayEditAmount()) {
            return enterAmountSharedViewModel.hasAmount();
        } else {
            return viewModel.paymentIntent.hasAmount();
        }
    }

    private boolean everythingPlausible() {
        return viewModel.state == SendCoinsViewModel.State.INPUT && isPayeePlausible() && isAmountPlausible();
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
        setState(SendCoinsViewModel.State.SIGNING);

        // final payment intent
        final PaymentIntent finalPaymentIntent = viewModel.paymentIntent.mergeWithEditedValues(
                enterAmountSharedViewModel.getDashAmount(), null);

        SendRequest sendRequest = createSendRequest(finalPaymentIntent, true, viewModel.dryrunSendRequest.ensureMinRequiredFee);

        final Coin finalAmount = finalPaymentIntent.getAmount();

        sendRequest.memo = viewModel.paymentIntent.memo;
        sendRequest.exchangeRate = enterAmountSharedViewModel.getExchangeRate();
        log.info("Using exchange rate: " + (sendRequest.exchangeRate != null
                ? sendRequest.exchangeRate.coinToFiat(Coin.COIN).toFriendlyString() :
                "not available"));
        sendRequest.aesKey = encryptionKey;

        final Wallet wallet = viewModel.wallet;
        new SendCoinsOfflineTask(wallet, backgroundHandler) {
            @Override
            protected void onSuccess(@NonNull final Transaction transaction) {

                viewModel.sentTransaction = transaction;

                setState(SendCoinsViewModel.State.SENDING);

                final Address refundAddress = viewModel.paymentIntent.standard == Standard.BIP70
                        ? wallet.freshAddress(KeyPurpose.REFUND) : null;
                final Payment payment = PaymentProtocol.createPaymentMessage(
                        Arrays.asList(viewModel.sentTransaction), finalAmount, refundAddress, null,
                        viewModel.paymentIntent.payeeData);

                if (directPaymentEnableView.isChecked())
                    directPay(payment);

                application.broadcastTransaction(viewModel.sentTransaction);

                final ComponentName callingActivity = activity.getCallingActivity();
                if (callingActivity != null) {
                    log.info("returning result to calling activity: {}", callingActivity.flattenToString());

                    final Intent result = new Intent();
                    BitcoinIntegration.transactionHashToResult(result, viewModel.sentTransaction.getHashAsString());
                    if (viewModel.paymentIntent.standard == Standard.BIP70)
                        BitcoinIntegration.paymentToResult(result, payment.toByteArray());
                    activity.setResult(Activity.RESULT_OK, result);
                }
                showTransactionResult(viewModel.sentTransaction, wallet);
                playSentSound();
                activity.finish();
            }

            private void directPay(final Payment payment) {
                final DirectPaymentTask.ResultCallback callback = new DirectPaymentTask.ResultCallback() {
                    @Override
                    public void onResult(final boolean ack) {
                        viewModel.directPaymentAck = ack;

                        if (viewModel.state == SendCoinsViewModel.State.SENDING)
                            setState(SendCoinsViewModel.State.SENT);

                        updateView();
                    }

                    @Override
                    public void onFail(final int messageResId, final Object... messageArgs) {
                        final DialogBuilder dialog = DialogBuilder.warn(activity,
                                R.string.send_coins_fragment_direct_payment_failed_title);
                        dialog.setMessage(viewModel.paymentIntent.paymentUrl + "\n" + getString(messageResId, messageArgs)
                                + "\n\n" + getString(R.string.send_coins_fragment_direct_payment_failed_msg));
                        dialog.setPositiveButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(final DialogInterface dialog, final int which) {
                                directPay(payment);
                            }
                        });
                        dialog.setNegativeButton(R.string.button_dismiss, null);
                        dialog.show();
                    }
                };

                if (viewModel.paymentIntent.isHttpPaymentUrl()) {
                    new DirectPaymentTask.HttpPaymentTask(backgroundHandler, callback, viewModel.paymentIntent.paymentUrl,
                            application.httpUserAgent()).send(payment);
                } else if (viewModel.paymentIntent.isBluetoothPaymentUrl() && bluetoothAdapter != null
                        && bluetoothAdapter.isEnabled()) {
                    new DirectPaymentTask.BluetoothPaymentTask(backgroundHandler, callback, bluetoothAdapter,
                            Bluetooth.getBluetoothMac(viewModel.paymentIntent.paymentUrl)).send(payment);
                }
            }

            @Override
            protected void onInsufficientMoney(final Coin missing) {
                setState(SendCoinsViewModel.State.INPUT);

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

            @Override
            protected void onInvalidEncryptionKey() {
                setState(SendCoinsViewModel.State.INPUT);
            }

            @Override
            protected void onEmptyWalletFailed() {
                setState(SendCoinsViewModel.State.INPUT);

                final DialogBuilder dialog = DialogBuilder.warn(activity,
                        R.string.send_coins_fragment_empty_wallet_failed_title);
                dialog.setMessage(R.string.send_coins_fragment_hint_empty_wallet_failed);
                dialog.setNeutralButton(R.string.button_dismiss, null);
                dialog.show();
            }

            @Override
            protected void onFailure(Exception exception) {
                setState(SendCoinsViewModel.State.FAILED);

                final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_error_msg);
                dialog.setMessage(exception.toString());
                dialog.setNeutralButton(R.string.button_dismiss, null);
                dialog.show();
            }
        }.sendCoinsOffline(sendRequest); // send asynchronously
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

    private Runnable dryrunRunnable = new Runnable() {
        @Override
        public void run() {
            if (viewModel.state == SendCoinsViewModel.State.INPUT)
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

    private void setState(final SendCoinsViewModel.State state) {
        viewModel.state = state;

        updateView();
    }

    @SuppressLint("SetTextI18n")
    private void updateView() {

        if (viewModel.paymentIntent == null) {
            log.info("viewModel.paymentIntent == null");
            return;
        }

        if (viewModel.paymentIntent.hasPayee()) {
            payeeNameView.setVisibility(View.VISIBLE);
            payeeNameView.setText(viewModel.paymentIntent.payeeName);

            payeeVerifiedByView.setVisibility(View.VISIBLE);
            final String verifiedBy = viewModel.paymentIntent.payeeVerifiedBy != null ? viewModel.paymentIntent.payeeVerifiedBy : getString(R.string.send_coins_fragment_payee_verified_by_unknown);
            payeeVerifiedByView.setText(CHAR_CHECKMARK + String.format(getString(R.string.send_coins_fragment_payee_verified_by), verifiedBy));
        } else {
            payeeNameView.setVisibility(View.GONE);
            payeeVerifiedByView.setVisibility(View.GONE);
        }

        if (viewModel.paymentIntent.hasOutputs()) {
            if (viewModel.paymentIntent.hasAddress())
                receivingStaticAddressView.setText(viewModel.paymentIntent.getAddress().toBase58());
            else
                receivingStaticAddressView.setText(R.string.send_coins_fragment_receiving_address_complex);
        }

        enterAmountSharedViewModel.getDirectionChangeEnabledData().setValue(
                viewModel.state == SendCoinsViewModel.State.INPUT && viewModel.paymentIntent.mayEditAmount());

        final boolean directPaymentVisible;
        if (viewModel.paymentIntent.hasPaymentUrl()) {
            if (viewModel.paymentIntent.isBluetoothPaymentUrl())
                directPaymentVisible = bluetoothAdapter != null;
            else
                directPaymentVisible = !Constants.BUG_OPENSSL_HEARTBLEED;
        } else {
            directPaymentVisible = false;
        }
        directPaymentEnableView.setVisibility(directPaymentVisible ? View.VISIBLE : View.GONE);
        directPaymentEnableView.setEnabled(viewModel.state == SendCoinsViewModel.State.INPUT);

        enterAmountSharedViewModel.getMessageTextStringData().setValue(null);
        if (viewModel.state == SendCoinsViewModel.State.INPUT) {
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

        if (viewModel.directPaymentAck != null) {
            directPaymentMessageView.setVisibility(View.VISIBLE);
            directPaymentMessageView.setText(viewModel.directPaymentAck ? R.string.send_coins_fragment_direct_payment_ack
                    : R.string.send_coins_fragment_direct_payment_nack);
        } else {
            directPaymentMessageView.setVisibility(View.GONE);
        }

        enterAmountSharedViewModel.getButtonEnabledData().setValue(everythingPlausible()
                && (!isUserAuthorized() || viewModel.dryrunSendRequest != null)
                && (blockchainState == null || !blockchainState.getReplaying()));

        if (viewModel.state == null || viewModel.state == SendCoinsViewModel.State.REQUEST_PAYMENT_REQUEST) {
            enterAmountSharedViewModel.getButtonTextData().call(0);
        } else if (viewModel.state == SendCoinsViewModel.State.INPUT) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_fragment_button_send);
        } else if (viewModel.state == SendCoinsViewModel.State.DECRYPTING) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_fragment_state_decrypting);
        } else if (viewModel.state == SendCoinsViewModel.State.SIGNING) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_preparation_msg);
        } else if (viewModel.state == SendCoinsViewModel.State.SENDING) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_sending_msg);
        } else if (viewModel.state == SendCoinsViewModel.State.SENT) {
            enterAmountSharedViewModel.getButtonTextData().call(R.string.send_coins_sent_msg);
        } else if (viewModel.state == SendCoinsViewModel.State.FAILED) {
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

    private void initStateFromIntentExtras(final Bundle extras) {
        final PaymentIntent paymentIntent = extras.getParcelable(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT);

        updateStateFrom(paymentIntent);
    }

    private void initStateFromDashUri(final Uri dashUri) {
        final String input = dashUri.toString();

        new StringInputParser(input) {
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

    private void initStateFromPaymentRequest(final String mimeType, final byte[] input) {
        new BinaryInputParser(mimeType, input) {
            @Override
            protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                updateStateFrom(paymentIntent);
            }

            @Override
            protected void error(final int messageResId, final Object... messageArgs) {
                dialog(activity, activityDismissListener, 0, messageResId, messageArgs);
            }
        }.parse();
    }

    private void initStateFromIntentUri(final String mimeType, final Uri bitcoinUri) {
        try {
            final InputStream is = activity.getContentResolver().openInputStream(bitcoinUri);

            new StreamInputParser(mimeType, is) {
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

    private void updateStateFrom(final PaymentIntent paymentIntent) {
        log.info("got {}", paymentIntent);

        viewModel.paymentIntent = paymentIntent;

        viewModel.directPaymentAck = null;

        // delay these actions until fragment is resumed
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (paymentIntent.hasPaymentRequestUrl() && paymentIntent.isBluetoothPaymentRequestUrl()) {
                    if (bluetoothAdapter.isEnabled())
                        requestPaymentRequest();
                    else
                        // ask for permission to enable bluetooth
                        startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                                REQUEST_CODE_ENABLE_BLUETOOTH_FOR_PAYMENT_REQUEST);
                } else if (paymentIntent.hasPaymentRequestUrl() && paymentIntent.isHttpPaymentRequestUrl()
                        && !Constants.BUG_OPENSSL_HEARTBLEED) {
                    requestPaymentRequest();
                } else {
                    setState(SendCoinsViewModel.State.INPUT);

                    enterAmountSharedViewModel.getChangeDashAmountEvent().setValue(paymentIntent.getAmount());

                    if (paymentIntent.isBluetoothPaymentUrl())
                        directPaymentEnableView.setChecked(bluetoothAdapter != null && bluetoothAdapter.isEnabled());
                    else if (paymentIntent.isHttpPaymentUrl())
                        directPaymentEnableView.setChecked(!Constants.BUG_OPENSSL_HEARTBLEED);

                    updateView();
                    handler.post(dryrunRunnable);
                }
            }
        });
    }

    private void requestPaymentRequest() {
        final String host;
        if (!Bluetooth.isBluetoothUrl(viewModel.paymentIntent.paymentRequestUrl))
            host = Uri.parse(viewModel.paymentIntent.paymentRequestUrl).getHost();
        else
            host = Bluetooth.decompressMac(Bluetooth.getBluetoothMac(viewModel.paymentIntent.paymentRequestUrl));

        ProgressDialogFragment.showProgress(fragmentManager,
                getString(R.string.send_coins_fragment_request_payment_request_progress, host));
        setState(SendCoinsViewModel.State.REQUEST_PAYMENT_REQUEST);

        final RequestPaymentRequestTask.ResultCallback callback = new RequestPaymentRequestTask.ResultCallback() {
            @Override
            public void onPaymentIntent(final PaymentIntent paymentIntent) {
                ProgressDialogFragment.dismissProgress(fragmentManager);

                if (viewModel.paymentIntent.isExtendedBy(paymentIntent)) {
                    // success
                    setState(SendCoinsViewModel.State.INPUT);
                    updateStateFrom(paymentIntent);
                    updateView();
                    handler.post(dryrunRunnable);
                } else {
                    final StringBuilder reasons = new StringBuilder();
                    if (!viewModel.paymentIntent.equalsAddress(paymentIntent))
                        reasons.append("address");
                    if (!viewModel.paymentIntent.equalsAmount(paymentIntent))
                        reasons.append(reasons.length() == 0 ? "" : ", ").append("amount");
                    if (reasons.length() == 0)
                        reasons.append("unknown");

                    final DialogBuilder dialog = DialogBuilder.warn(activity,
                            R.string.send_coins_fragment_request_payment_request_failed_title);
                    dialog.setMessage(getString(R.string.send_coins_fragment_request_payment_request_wrong_signature)
                            + "\n\n" + reasons);
                    dialog.singleDismissButton(new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialog, final int which) {
                            handleCancel();
                        }
                    });
                    dialog.show();

                    log.info("BIP72 trust check failed: {}", reasons);
                }
            }

            @Override
            public void onFail(final int messageResId, final Object... messageArgs) {
                ProgressDialogFragment.dismissProgress(fragmentManager);

                final DialogBuilder dialog = DialogBuilder.warn(activity,
                        R.string.send_coins_fragment_request_payment_request_failed_title);
                dialog.setMessage(getString(messageResId, messageArgs));
                dialog.setPositiveButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        requestPaymentRequest();
                    }
                });
                dialog.setNegativeButton(R.string.button_dismiss, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        if (!viewModel.paymentIntent.hasOutputs())
                            handleCancel();
                        else
                            setState(SendCoinsViewModel.State.INPUT);
                    }
                });
                dialog.show();
            }
        };

        if (!Bluetooth.isBluetoothUrl(viewModel.paymentIntent.paymentRequestUrl))
            new RequestPaymentRequestTask.HttpRequestTask(backgroundHandler, callback, application.httpUserAgent())
                    .requestPaymentRequest(viewModel.paymentIntent.paymentRequestUrl);
        else
            new RequestPaymentRequestTask.BluetoothRequestTask(backgroundHandler, callback, bluetoothAdapter)
                    .requestPaymentRequest(viewModel.paymentIntent.paymentRequestUrl);
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

        DialogFragment dialog = ConfirmTransactionDialog.createDialog(address, amountStr, amountFiat, fiatSymbol, fee, total, null);
        dialog.show(getFragmentManager(), "ConfirmTransactionDialog");
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
