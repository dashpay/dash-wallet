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
import android.content.Intent;
import android.graphics.Typeface;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelProviders;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.bitcoinj.wallet.Wallet.CouldNotAdjustDownwards;
import org.bitcoinj.wallet.Wallet.DustySendRequested;
import org.dash.wallet.common.services.AuthenticationManager;
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog;
import org.dash.wallet.common.util.GenericUtils;
import org.dashj.platform.dashpay.BlockchainIdentity;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.data.DashPayContactRequest;
import de.schildbach.wallet.data.DashPayProfile;
import org.dash.wallet.common.data.BlockchainState;

import javax.inject.Inject;

import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.data.UsernameSearchResult;
import de.schildbach.wallet.integration.android.BitcoinIntegration;
import de.schildbach.wallet.livedata.Resource;
import de.schildbach.wallet.livedata.Status;
import de.schildbach.wallet.ui.CheckPinDialog;
import de.schildbach.wallet.ui.dashpay.DashPayViewModel;
import de.schildbach.wallet.ui.dashpay.PlatformRepo;
import de.schildbach.wallet.ui.CheckPinDialog;
import de.schildbach.wallet.ui.SingleActionSharedViewModel;
import de.schildbach.wallet.ui.transactions.TransactionResultActivity;
import de.schildbach.wallet_test.R;
import kotlin.Unit;
import static java.lang.Math.min;

@AndroidEntryPoint
public class SendCoinsFragment extends Fragment {

    protected final Handler handler = new Handler();

    private static final Logger log = LoggerFactory.getLogger(SendCoinsFragment.class);

    private SendCoinsViewModel viewModel;
    private EnterAmountSharedViewModel enterAmountSharedViewModel;
    private DashPayViewModel dashPayViewModel;
    @Inject AuthenticationManager authManager;

    private boolean wasAmountChangedByTheUser = false;

    private boolean userAuthorizedDuring = false;

    private BlockchainState blockchainState;

    private boolean autoAcceptContactRequest = false;

    private boolean isUserAuthorized() {
        return ((SendCoinsActivity)requireActivity()).isUserAuthorized() || userAuthorizedDuring;
    }

    @Override
    public void onViewCreated(@NonNull @NotNull View view, @Nullable @org.jetbrains.annotations.Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = ViewModelProviders.of(this).get(SendCoinsViewModel.class);
        viewModel.getBasePaymentIntent().observe(getViewLifecycleOwner(), new Observer<Resource<PaymentIntent>>() {
            @Override
            public void onChanged(Resource<PaymentIntent> paymentIntentResource) {
                switch (paymentIntentResource.getStatus()) {
                    case LOADING: {
                        break;
                    }
                    case SUCCESS: {
                        PaymentIntent paymentIntent = Objects.requireNonNull(paymentIntentResource.getData());
                        if (paymentIntent.hasPaymentRequestUrl()) {
                            throw new IllegalArgumentException(PaymentProtocolFragment.class.getSimpleName()
                                    + "class should be used to handle Payment requests (BIP70 and BIP270)");
                        } else {
                            updateStateFrom(paymentIntent);
                        }
                        break;
                    }
                    case ERROR: {
                        String errorMessage = paymentIntentResource.getMessage();

                        if (errorMessage == null) {
                            errorMessage = getString(R.string.error);
                        }

                        AdaptiveDialog.simple(
                                errorMessage,
                                getString(R.string.button_dismiss),
                                ""
                        ).show(requireActivity(), result -> {
                            requireActivity().finish();
                            return Unit.INSTANCE;
                        });
                        break;
                    }
                }
            }
        });

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(getViewLifecycleOwner(), new Observer<BlockchainState>() {
            @Override
            public void onChanged(BlockchainState blockchainState) {
                SendCoinsFragment.this.blockchainState = blockchainState;
                updateView();
            }
        });

        enterAmountSharedViewModel = ViewModelProviders.of(requireActivity()).get(EnterAmountSharedViewModel.class);
        enterAmountSharedViewModel.getDashAmountData().observe(getViewLifecycleOwner(), new Observer<Coin>() {
            @Override
            public void onChanged(Coin amount) {
                if (!wasAmountChangedByTheUser) {
                    wasAmountChangedByTheUser = Coin.ZERO.isLessThan(amount);
                }
                handler.post(dryrunRunnable);
            }
        });
        enterAmountSharedViewModel.getButtonClickEvent().observe(getViewLifecycleOwner(), coin -> authenticateOrConfirm());
        enterAmountSharedViewModel.getMaxButtonClickEvent().observe(getViewLifecycleOwner(), unused -> {
            if (isUserAuthorized()) {
                handleEmpty();
            } else {
                authManager.authenticate(requireActivity(), false, pin -> {
                    if (pin != null) {
                        userAuthorizedDuring = true;
                        handleEmpty();
                    }
                    return Unit.INSTANCE;
                });
            }
        });
        final ConfirmTransactionDialog.SharedViewModel confirmTransactionSharedViewModel
                = new ViewModelProvider(requireActivity()).get(ConfirmTransactionDialog.SharedViewModel.class);
        confirmTransactionSharedViewModel.getClickConfirmButtonEvent().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                autoAcceptContactRequest = confirmTransactionSharedViewModel.getAutoAcceptContactRequest();
                handleGo();
            }
        });
        viewModel.state.observe(getViewLifecycleOwner(), new Observer<SendCoinsViewModel.State>() {
            @Override
            public void onChanged(SendCoinsViewModel.State state) {
                updateView();
            }
        });
        viewModel.getOnSendCoinsOffline().observe(getViewLifecycleOwner(), new Observer<kotlin.Pair<SendCoinsViewModel.SendCoinsOfflineStatus, Object>>() {
            @Override
            public void onChanged(kotlin.Pair<SendCoinsViewModel.SendCoinsOfflineStatus, Object> sendCoinsData) {

                SendCoinsViewModel.SendCoinsOfflineStatus status = sendCoinsData.getFirst();
                switch (status) {
                    case SUCCESS: {
                        viewModel.state.setValue(SendCoinsViewModel.State.SENDING);
                        SendRequest sendRequest = (SendRequest) sendCoinsData.getSecond();
                        onSignAndSendPaymentSuccess(sendRequest.tx);
                        break;
                    }
                    case INSUFFICIENT_MONEY: {
                        viewModel.state.setValue(SendCoinsViewModel.State.INPUT);
                        Coin missing = (Coin) sendCoinsData.getSecond();
                        showInsufficientMoneyDialog(missing);
                        break;
                    }
                    case INVALID_ENCRYPTION_KEY:
                    case CANCELED: {
                        viewModel.state.setValue(SendCoinsViewModel.State.INPUT);
                        break;
                    }
                    case EMPTY_WALLET_FAILED: {
                        viewModel.state.setValue(SendCoinsViewModel.State.INPUT);
                        showEmptyWalletFailedDialog();
                        break;
                    }
                    case FAILURE: {
                        viewModel.state.setValue(SendCoinsViewModel.State.FAILED);
                        Exception exception = (Exception) sendCoinsData.getSecond();
                        showFailureDialog(exception);
                        break;
                    }
                }
            }
        });

        dashPayViewModel = new ViewModelProvider(requireActivity()).get(DashPayViewModel.class);

        if (savedInstanceState == null) {
            final Intent intent = requireActivity().getIntent();

            Bundle extras = Objects.requireNonNull(intent.getExtras());
            final PaymentIntent paymentIntent = extras.getParcelable(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT);
            if (paymentIntent == null || paymentIntent.hasPaymentRequestUrl()) {
                throw new IllegalArgumentException();
            }

            BlockchainIdentity blockchainIdentity = PlatformRepo.getInstance().getBlockchainIdentity();
            boolean isDashUserOrNotMe = blockchainIdentity != null;
            // make sure that this payment intent is not to me
            if (paymentIntent.isIdentityPaymentRequest() && paymentIntent.payeeUsername != null &&
                    blockchainIdentity != null &&
                    blockchainIdentity.getCurrentUsername() != null &&
                    paymentIntent.payeeUsername.equals(blockchainIdentity.getCurrentUsername())) {
                isDashUserOrNotMe = false;
            }

            if (isDashUserOrNotMe && paymentIntent.isIdentityPaymentRequest()) {
                if (paymentIntent.payeeUsername != null) {
                    viewModel.loadUserDataByUsername(paymentIntent.payeeUsername).observe(getViewLifecycleOwner(), new Observer<Resource<UsernameSearchResult>>() {
                        @Override
                        public void onChanged(Resource<UsernameSearchResult> result) {
                            if (result.getStatus() == Status.SUCCESS && result.getData() != null) {
                                handleDashIdentity(result.getData(), paymentIntent);
                            } else {
                                log.error("error loading identity for username {}", paymentIntent.payeeUsername);
                                Toast.makeText(getContext(), "error loading identity", Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                } else if (paymentIntent.payeeUserId != null) {
                    viewModel.loadUserDataByUserId(paymentIntent.payeeUserId).observe(getViewLifecycleOwner(), new Observer<Resource<UsernameSearchResult>>() {
                        @Override
                        public void onChanged(Resource<UsernameSearchResult> result) {
                            if (result.getStatus() == Status.SUCCESS && result.getData() != null) {
                                handleDashIdentity(result.getData(), paymentIntent);
                            } else {
                                log.error("error loading identity for userId {}", paymentIntent.payeeUserId);
                                Toast.makeText(getContext(), "error loading identity", Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                } else {
                    throw new IllegalStateException("not identity payment request");
                }
            } else {
                viewModel.getBasePaymentIntent().setValue(Resource.success(paymentIntent));
            }
        }
    }

    private void handleDashIdentity(UsernameSearchResult userData, PaymentIntent paymentIntent) {
        viewModel.setUserData(userData);
        if (userData.getRequestReceived()) {
            final DashPayProfile dashPayProfile = userData.getDashPayProfile();
            AppDatabase.getAppDatabase().dashPayContactRequestDaoAsync()
                    .loadDistinctToOthers(dashPayProfile.getUserId())
                    .observe(getViewLifecycleOwner(), new Observer<List<DashPayContactRequest>>() {
                        @Override
                        public void onChanged(List<DashPayContactRequest> dashPayContactRequests) {
                            if (dashPayContactRequests != null && dashPayContactRequests.size() > 0) {
                                HashMap<Long, DashPayContactRequest> map = new HashMap<>(dashPayContactRequests.size());

                                // This is currently using the first version, but it should use the version specified
                                // in the ContactInfo.accountRef related to this contact.  Ideally the user should
                                // approve of a change to the "accountReference" that is used.
                                long firstTimestamp = System.currentTimeMillis();
                                for (DashPayContactRequest contactRequest: dashPayContactRequests) {
                                    map.put(contactRequest.getTimestamp(), contactRequest);
                                    firstTimestamp = min(firstTimestamp, contactRequest.getTimestamp());
                                }
                                DashPayContactRequest mostRecentContactRequest = map.get(firstTimestamp);
                                Address address = dashPayViewModel.getNextContactAddress(dashPayProfile.getUserId(), (int)mostRecentContactRequest.getAccountReference());
                                PaymentIntent payToAddress = PaymentIntent.fromAddressWithIdentity(
                                        Address.fromBase58(Constants.NETWORK_PARAMETERS, address.toBase58()),
                                        dashPayProfile.getUserId(), paymentIntent.getAmount());
                                viewModel.getBasePaymentIntent().setValue(Resource.success(payToAddress));
                                enterAmountSharedViewModel.getDashPayProfileData().setValue(dashPayProfile);

                                if (paymentIntent.getAmount() != null && paymentIntent.getAmount().isGreaterThan(Coin.ZERO)) {
                                    if (blockchainState != null && !blockchainState.getReplaying()) {
                                        authenticateOrConfirm();
                                    }
                                }
                            }
                        }
                    });

        } else {
            viewModel.getBasePaymentIntent().setValue(Resource.success(paymentIntent));
        }
    }

    private void updateStateFrom(final PaymentIntent paymentIntent) {
        log.info("got {}", paymentIntent);

        // delay these actions until fragment is resumed
        handler.post(new Runnable() {
            @Override
            public void run() {

                // If an amount is specified (in Dash), then set the active currency to Dash
                // If amount is 0 Dash or not specified, then don't change the active currency
                Coin amount = paymentIntent.getAmount();
                if (amount != null && !amount.isZero()) {
                    enterAmountSharedViewModel.setDashToFiatDirection(true);
                }
                enterAmountSharedViewModel.getChangeDashAmountEvent().setValue(paymentIntent.getAmount());
                viewModel.state.setValue(SendCoinsViewModel.State.INPUT);
                handler.post(dryrunRunnable);
            }
        });
    }

    private void authenticateOrConfirm() {
        if (everythingPlausible()) {
            if (!isUserAuthorized() || viewModel.isSpendingConfirmationEnabled()) {
                Coin thresholdAmount = Coin.parseCoin(
                        Float.valueOf(viewModel.getBiometricLimit()).toString());
                boolean withinLimit = enterAmountSharedViewModel.getDashAmount().isLessThan(thresholdAmount);

                authManager.authenticate(requireActivity(), !withinLimit, pin -> {
                    if (pin != null) {
                        userAuthorizedDuring = true;
                        if (everythingPlausible() && viewModel.dryrunSendRequest != null) {
                            showPaymentConfirmation();
                        } else {
                            updateView();
                        }
                    }

                    return Unit.INSTANCE;
                });
            } else if (viewModel.dryrunException == null) {
                showPaymentConfirmation();
            }
        }
        updateView();
    }

    private SendCoinsViewModel.State getState() {
        return viewModel.state.getValue();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.send_coins_fragment, container);
    }

    @Override
    public void onResume() {
        super.onResume();

        handler.post(dryrunRunnable);
    }

    @Override
    public void onDetach() {
        handler.removeCallbacksAndMessages(null);

        super.onDetach();
    }

    private boolean isPayeePlausible() {
        return viewModel.getBasePaymentIntentValue().hasOutputs();
    }

    private boolean isAmountPlausible() {
        if (viewModel.getBasePaymentIntentValue().mayEditAmount()) {
            return enterAmountSharedViewModel.hasAmount();
        } else {
            return viewModel.getBasePaymentIntentValue().hasAmount();
        }
    }

    private boolean everythingPlausible() {
        return getState() == SendCoinsViewModel.State.INPUT && isPayeePlausible() && isAmountPlausible();
    }

    private void handleGo() {
        if (viewModel.dryrunSendRequest == null) {
            log.error("illegal state dryrunSendRequest == null");
            return;
        }
        Coin editedAmount = enterAmountSharedViewModel.getDashAmount();
        ExchangeRate exchangeRate = enterAmountSharedViewModel.getExchangeRate();

        viewModel.signAndSendPayment(editedAmount, exchangeRate);
    }

    private void onSignAndSendPaymentSuccess(Transaction transaction) {
        final ComponentName callingActivity = requireActivity().getCallingActivity();
        if (callingActivity != null) {
            log.info("returning result to calling activity: {}", callingActivity.flattenToString());
            Intent resultIntent = new Intent();
            BitcoinIntegration.transactionHashToResult(resultIntent, viewModel.sentTransaction.getTxId().toString());
            requireActivity().setResult(Activity.RESULT_OK, resultIntent);
        }
        showTransactionResult(viewModel.sentTransaction);
        playSentSound();
        requireActivity().finish();
    }

    private void showInsufficientMoneyDialog(Coin missing) {

        final Wallet wallet = viewModel.getWallet();
        final Coin estimated = wallet.getBalance(BalanceType.ESTIMATED);
        final Coin available = wallet.getBalance(BalanceType.AVAILABLE);
        final Coin pending = estimated.subtract(available);

        final MonetaryFormat dashFormat = viewModel.getDashFormat();
        final StringBuilder msg = new StringBuilder();
        msg.append(getString(R.string.send_coins_fragment_insufficient_money_msg1, dashFormat.format(missing)));

        if (pending.signum() > 0)
            msg.append("\n\n")
                    .append(getString(R.string.send_coins_fragment_pending, dashFormat.format(pending)));
        if (viewModel.getBasePaymentIntentValue().mayEditAmount())
            msg.append("\n\n").append(getString(R.string.send_coins_fragment_insufficient_money_msg2));

        boolean mayEditAmount = viewModel.getBasePaymentIntentValue().mayEditAmount();
        String positiveAction = "";
        String negativeAction = "";

        if (mayEditAmount) {
            positiveAction = getString(R.string.send_coins_options_empty);
            negativeAction = getString(R.string.button_cancel);
        } else {
            negativeAction = getString(R.string.button_dismiss);
        }

        AdaptiveDialog.create(
                R.drawable.ic_warning_filled,
                getString(R.string.send_coins_fragment_insufficient_money_title),
                msg.toString(),
                negativeAction,
                positiveAction
        ).show(requireActivity(), result -> {
            if (mayEditAmount && result != null && result) {
                handleEmpty();
            }
            return Unit.INSTANCE;
        });
    }

    private void showEmptyWalletFailedDialog() {
        AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.send_coins_fragment_empty_wallet_failed_title),
                getString(R.string.send_coins_fragment_hint_empty_wallet_failed),
                getString(R.string.button_dismiss),
                null
        ).show(requireActivity(), result -> Unit.INSTANCE);
    }

    private void showFailureDialog(Exception exception) {
        AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.send_coins_error_msg),
                exception.toString(),
                getString(R.string.button_dismiss),
                null
        ).show(requireActivity(), result -> Unit.INSTANCE);
    }

    private void showTransactionResult(Transaction transaction) {
        if (!isAdded()) {
            return;
        }

        if (autoAcceptContactRequest && viewModel.getUserData() != null) {
            dashPayViewModel.sendContactRequest(viewModel.getUserData().getDashPayProfile().getUserId());
        }
        Intent transactionResultIntent = TransactionResultActivity.createIntent(requireActivity(),
                requireActivity().getIntent().getAction(), transaction, isUserAuthorized(),
                viewModel.getUserData());
        startActivity(transactionResultIntent);
    }

    private void handleEmpty() {
        final Coin available = viewModel.getWallet().getBalance(BalanceType.ESTIMATED);
        enterAmountSharedViewModel.getApplyMaxAmountEvent().setValue(available);

        handler.post(dryrunRunnable);
    }

    private Runnable dryrunRunnable = new Runnable() {
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
            final Wallet wallet = viewModel.getWallet();
            final Address dummyAddress = wallet.currentReceiveAddress(); // won't be used, tx is never committed

            if (Coin.ZERO.equals(amount)) {
                return;
            }

            final PaymentIntent finalPaymentIntent = viewModel.getBasePaymentIntentValue().mergeWithEditedValues(amount, dummyAddress);

            try {
                // check regular payment
                SendRequest sendRequest = viewModel.createSendRequest(finalPaymentIntent, false, false);

                wallet.completeTx(sendRequest);
                if (viewModel.checkDust(sendRequest)) {
                    sendRequest = viewModel.createSendRequest(finalPaymentIntent, false, true);
                    wallet.completeTx(sendRequest);
                }
                viewModel.dryrunSendRequest = sendRequest;
            } catch (final Exception x) {
                viewModel.dryrunException = x;
            }
        }
    };

    @SuppressLint("SetTextI18n")
    protected void updateView() {

        if (!viewModel.getBasePaymentIntentReady()) {
            return;
        }

        enterAmountSharedViewModel.getDirectionChangeEnabledData().setValue(
                getState() == SendCoinsViewModel.State.INPUT && viewModel.getBasePaymentIntentValue().mayEditAmount());

        enterAmountSharedViewModel.getMessageTextStringData().setValue(null);
        if (getState() == SendCoinsViewModel.State.INPUT) {
            CharSequence message = null;
            if (blockchainState != null && blockchainState.getReplaying()) {
                message = coloredString(getString(R.string.send_coins_fragment_hint_replaying), R.color.dash_red, true);
                enterAmountSharedViewModel.getMessageTextStringData().setValue(message);
            } else {
                if (Coin.ZERO.equals(enterAmountSharedViewModel.getDashAmount()) && wasAmountChangedByTheUser) {
                    message = coloredString(getString(R.string.send_coins_error_dusty_send), R.color.dash_red, true);
                } else if (viewModel.dryrunException != null) {
                    if (viewModel.dryrunException instanceof DustySendRequested)
                        message = coloredString(getString(R.string.send_coins_error_dusty_send), R.color.dash_red, true);
                    else if (viewModel.dryrunException instanceof InsufficientMoneyException) {
                        message = coloredString(getString(R.string.send_coins_error_insufficient_money), R.color.dash_red, true);
                    } else if (viewModel.dryrunException instanceof CouldNotAdjustDownwards) {
                        message = coloredString(getString(R.string.send_coins_error_dusty_send), R.color.dash_red, true);
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
        if (state == null) {
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

        String address = viewModel.getBasePaymentIntentValue().getAddress().toBase58();
        ExchangeRate rate = enterAmountSharedViewModel.getExchangeRate();
        // prevent crash if the exchange rate is null
        Fiat fiatAmount = rate != null ? rate.coinToFiat(amount) : null;

        String amountStr = MonetaryFormat.BTC.noCode().format(amount).toString();
        // if the exchange rate is not available, then show "Not Available"
        String amountFiat = fiatAmount != null ? Constants.LOCAL_FORMAT.format(fiatAmount).toString() : getString(R.string.transaction_row_rate_not_available);
        String fiatSymbol = fiatAmount != null ? GenericUtils.currencySymbol(fiatAmount.currencyCode) : "";
        String fee = txFee.toPlainString();

        DashPayProfile dashPayProfile = null;
        if (viewModel.getUserData() != null && viewModel.getUserData().getRequestReceived()) {
            dashPayProfile = viewModel.getUserData().getDashPayProfile();
        }
        boolean isPendingContactRequest = viewModel.getUserData() != null && viewModel.getUserData().isPendingRequest();
        String username = dashPayProfile != null ? dashPayProfile.getUsername() : null;
        String displayName = dashPayProfile == null || dashPayProfile.getDisplayName().isEmpty() ? username : dashPayProfile.getDisplayName();
        String avatarUrl = dashPayProfile != null ? dashPayProfile.getAvatarUrl() : null;

        ConfirmTransactionDialog.showDialog(requireActivity(), address, amountStr, amountFiat,
                fiatSymbol, fee, total, null, null, null,
                username, displayName, avatarUrl, isPendingContactRequest);
    }


    private void playSentSound() {
        // play sound effect
        final int soundResId = getResources().getIdentifier("send_coins_broadcast_1",
                "raw", requireActivity().getPackageName());
        if (soundResId > 0)
            RingtoneManager
                    .getRingtone(requireActivity(), Uri.parse(
                            "android.resource://" + requireActivity().getPackageName() + "/" + soundResId))
                    .play();
    }
}
