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

package de.schildbach.wallet.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.dash.wallet.common.util.GenericUtils;

import javax.annotation.Nullable;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.data.DashPayProfile;
import de.schildbach.wallet.rates.ExchangeRate;
import de.schildbach.wallet.rates.ExchangeRatesViewModel;
import de.schildbach.wallet.ui.dashpay.NotificationsActivity;
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay;
import de.schildbach.wallet_test.R;

@AndroidEntryPoint
public final class HeaderBalanceFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private WalletApplication application;
    private AbstractBindServiceActivity activity;
    private Configuration config;

    private Boolean hideBalance;
    private View showBalanceButton;
    private TextView hideShowBalanceHint;
    private TextView caption;
    private View view;
    private CurrencyTextView viewBalanceDash;
    private CurrencyTextView viewBalanceLocal;
    private TextView notifications;
    private ImageButton notificationBell;
    private ImageView dashpayUserAvatar;
    private TextView syncingIndicator;

    private boolean showLocalBalance;

    private HeaderBalanceViewModel viewModel;
    private MainActivityViewModel mainActivityViewModel;
    private ExchangeRatesViewModel exchangeRatesViewModel;

    @Nullable
    private ExchangeRate exchangeRate = null;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractBindServiceActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        hideBalance = config.getHideBalance();

        showLocalBalance = getResources().getBoolean(R.bool.show_local_balance);
    }

    @Override
    public void onActivityCreated(@androidx.annotation.Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        initViewModel();
        setNotificationCount();
    }

    private void initViewModel() {
        exchangeRatesViewModel = new ViewModelProvider(this).get(ExchangeRatesViewModel.class);

        mainActivityViewModel = new ViewModelProvider(requireActivity()).get(MainActivityViewModel.class);
        mainActivityViewModel.getBlockchainStateData().observe(getViewLifecycleOwner(), blockchainState -> {
            viewModel.setBlockchainState(blockchainState);
            updateView();
        });
        mainActivityViewModel.getDashPayProfileData().observe(getViewLifecycleOwner(), dashPayProfile -> {
            updateView();
            setNotificationCount();
        });

        viewModel = new ViewModelProvider(this).get(HeaderBalanceViewModel.class);
        viewModel.getWalletBalanceData().observe(getViewLifecycleOwner(), balance -> {
            updateView();
        });
        viewModel.getNotificationCountData().observe(getViewLifecycleOwner(), notificationCount -> {
            setNotificationCount();
        });
        // don't query alerts if notifications are disabled
        if (config.areNotificationsDisabled()) {
            long lastSeenNotification = config.getLastSeenNotificationTime();
            AppDatabase.getAppDatabase().userAlertDaoAsync()
                    .load(lastSeenNotification).observe(getViewLifecycleOwner(), userAlert -> {
                if (userAlert != null) {
                    viewModel.forceUpdateNotificationCount();
                }
            });
        }
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.header_balance_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        caption = view.findViewById(R.id.caption);
        hideShowBalanceHint = view.findViewById(R.id.hide_show_balance_hint);
        this.view = view;
        showBalanceButton = view.findViewById(R.id.show_balance_button);
        syncingIndicator = view.findViewById(R.id.balance_syncing_indicator);

        viewBalanceDash = view.findViewById(R.id.wallet_balance_dash);
        viewBalanceDash.setApplyMarkup(false);
        viewBalanceDash.setFormat(config.getFormat().noCode());
        viewBalanceDash.setAmount(Coin.ZERO);

        viewBalanceLocal = view.findViewById(R.id.wallet_balance_local);
        viewBalanceLocal.setInsignificantRelativeSize(1);

        view.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hideBalance = !hideBalance;
                updateView();
            }
        });

        notifications = view.findViewById(R.id.notifications);
        notificationBell = view.findViewById(R.id.notification_bell);
        dashpayUserAvatar = view.findViewById(R.id.dashpay_user_avatar);

        notifications.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(NotificationsActivity.createIntent(getContext(), NotificationsActivity.MODE_NOTIFICATIONS));
            }
        });

        notificationBell.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(NotificationsActivity.createIntent(requireContext(), NotificationsActivity.MODE_NOTIFICATIONS));
            }
        });

        dashpayUserAvatar.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(NotificationsActivity.createIntent(requireContext(), NotificationsActivity.MODE_NOTIFICATIONS));
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        exchangeRatesViewModel.getRate(config.getExchangeCurrencyCode()).observe(this,
                new Observer<ExchangeRate>() {
                    @Override
                    public void onChanged(ExchangeRate rate) {
                        if (rate != null) {
                            exchangeRate = rate;
                            updateView();
                        }
                    }
                });

        if (mainActivityViewModel.getHasIdentity()) {
            viewModel.forceUpdateNotificationCount();
        }
        if (config.getHideBalance()) {
            hideBalance = true;
        }
        config.registerOnSharedPreferenceChangeListener(this);

        updateView();
    }

    @Override
    public void onPause() {
        config.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    private void setNotificationCount() {
        int notificationCount = viewModel.getNotificationCount();
        if (!mainActivityViewModel.getHasIdentity()) {
            notifications.setVisibility(View.GONE);
            notificationBell.setVisibility(View.GONE);
        } else if (notificationCount > 0) {
            notifications.setText(String.valueOf(notificationCount));
            notifications.setVisibility(View.VISIBLE);
            notificationBell.setVisibility(View.GONE);
        } else if (notificationCount == 0) {
            notifications.setVisibility(View.GONE);
            notificationBell.setVisibility(View.VISIBLE);
        }
    }

    private void updateView() {
        View balances = view.findViewById(R.id.balances_layout);
        DashPayProfile dashPayProfile = mainActivityViewModel.getDashPayProfile();
        ProfilePictureDisplay.display(dashpayUserAvatar, dashPayProfile, true);

        if (hideBalance) {
            caption.setText(R.string.home_balance_hidden);
            hideShowBalanceHint.setText(R.string.home_balance_show_hint);
            balances.setVisibility(View.INVISIBLE);
            showBalanceButton.setVisibility(View.VISIBLE);
            return;
        }
        balances.setVisibility(View.VISIBLE);
        caption.setText(R.string.home_available_balance);
        hideShowBalanceHint.setText(R.string.home_balance_hide_hint);
        showBalanceButton.setVisibility(View.GONE);

        if (!isAdded()) {
            return;
        }

        BlockchainState blockchainState = mainActivityViewModel.getBlockchainState();
        if (blockchainState != null && !blockchainState.isSynced()) {
            syncingIndicator.setVisibility(View.VISIBLE);
            startSyncingIndicatorAnimation();
        } else {
            syncingIndicator.setVisibility(View.GONE);
            if (syncingIndicator.getAnimation() != null) {
                syncingIndicator.getAnimation().cancel();
            }
        }

        if (!showLocalBalance) {
            viewBalanceLocal.setVisibility(View.GONE);
        }

        Coin balance = viewModel.getWalletBalance();
        if (balance != null) {
            viewBalanceDash.setAmount(balance);

            if (showLocalBalance) {
                if (exchangeRate != null) {
                    org.bitcoinj.utils.ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(Coin.COIN,
                            exchangeRate.getFiat());
                    final Fiat localValue = rate.coinToFiat(balance);
                    viewBalanceLocal.setVisibility(View.VISIBLE);
                    String currencySymbol = GenericUtils.currencySymbol(exchangeRate.getCurrencyCode());
                    viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0, currencySymbol));
                    viewBalanceLocal.setAmount(localValue);
                } else {
                    viewBalanceLocal.setVisibility(View.INVISIBLE);
                }
            }
        } else {
            viewBalanceDash.setAmount(Coin.ZERO);
        }

        activity.invalidateOptionsMenu();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Configuration.PREFS_LAST_SEEN_NOTIFICATION_TIME.equals(key)) {
            viewModel.forceUpdateNotificationCount();
        }
    }

    private void startSyncingIndicatorAnimation() {
        Animation currentAnimation = syncingIndicator.getAnimation();
        if (currentAnimation == null || currentAnimation.hasEnded()) {
            AlphaAnimation alphaAnimation = new AlphaAnimation(0.2f, 0.8f);
            alphaAnimation.setDuration(833);
            alphaAnimation.setRepeatCount(Animation.INFINITE);
            alphaAnimation.setRepeatMode(Animation.REVERSE);
            syncingIndicator.startAnimation(alphaAnimation);
        }
    }

    private void showExchangeRatesActivity() {
        Intent intent = new Intent(getActivity(), ExchangeRatesActivity.class);
        getActivity().startActivity(intent);
    }
}
