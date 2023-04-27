/*
 * Copyright 2019 Dash Core Group.
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

package de.schildbach.wallet.ui.main;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;
import org.dash.wallet.common.data.entity.ExchangeRate;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay;
import org.dash.wallet.common.util.GenericUtils;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.database.entity.DashPayProfile;
import de.schildbach.wallet.ui.dashpay.NotificationsActivity;
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplayKt;
import de.schildbach.wallet_test.R;
import kotlinx.coroutines.ExperimentalCoroutinesApi;

@AndroidEntryPoint
@ExperimentalCoroutinesApi
public final class HeaderBalanceFragment extends Fragment {
    private View showBalanceButton;
    private TextView hideShowBalanceHint;
    private TextView caption;
    private View view;
    private CurrencyTextView viewBalanceDash;
    private CurrencyTextView viewBalanceLocal;
    private TextView syncingIndicator;
    private boolean showLocalBalance;
    private TextView notifications;
    private ImageButton notificationBell;
    private ImageView dashpayUserAvatar;

    private MainViewModel viewModel;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        showLocalBalance = getResources().getBoolean(R.bool.show_local_balance);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
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
        viewBalanceDash.setFormat(viewModel.getBalanceDashFormat());
        viewBalanceDash.setAmount(Coin.ZERO);

        viewBalanceLocal = view.findViewById(R.id.wallet_balance_local);
        viewBalanceLocal.setInsignificantRelativeSize(1);
        viewBalanceLocal.setStrikeThru(!Constants.IS_PROD_BUILD);

        view.setOnClickListener(v -> viewModel.triggerHideBalance());

        notifications = view.findViewById(R.id.notifications);
        notificationBell = view.findViewById(R.id.notification_bell);
        dashpayUserAvatar = view.findViewById(R.id.dashpay_user_avatar);

        notifications.setOnClickListener(v -> startActivity(
                NotificationsActivity.createIntent(requireContext(), NotificationsActivity.MODE_NOTIFICATIONS))
        );

        notificationBell.setOnClickListener(v -> startActivity(
                NotificationsActivity.createIntent(requireContext(), NotificationsActivity.MODE_NOTIFICATIONS))
        );

        dashpayUserAvatar.setOnClickListener(v -> startActivity(
                NotificationsActivity.createIntent(requireContext(), NotificationsActivity.MODE_NOTIFICATIONS))
        );

        viewModel.isBlockchainSynced().observe(getViewLifecycleOwner(), isSynced -> updateView());
        viewModel.getExchangeRate().observe(getViewLifecycleOwner(), rate -> updateView());
        viewModel.getBalance().observe(getViewLifecycleOwner(), balance -> updateView());
        viewModel.getHideBalance().observe(getViewLifecycleOwner(), toHide -> updateView());
        viewModel.getDashPayProfile().observe(getViewLifecycleOwner(), dashPayProfile -> {
            updateView();
            setNotificationCount();
        });
        viewModel.getNotificationCountData().observe(getViewLifecycleOwner(), count -> setNotificationCount());

        setNotificationCount();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateView();
    }

    private void updateView() {
        DashPayProfile dashPayProfile = viewModel.getDashPayProfile().getValue();
        ProfilePictureDisplayKt.display(ProfilePictureDisplay.Companion, dashpayUserAvatar, dashPayProfile, true);

        View balances = view.findViewById(R.id.balances_layout);
        Boolean hideBalance = viewModel.getHideBalance().getValue();

        if (hideBalance != null && hideBalance) {
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

        Boolean isSynced = viewModel.isBlockchainSynced().getValue();

        if (isSynced == null || !isSynced) {
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

        Coin balance = viewModel.getBalance().getValue();

        if (balance != null) {
            viewBalanceDash.setAmount(balance);

            if (showLocalBalance) {
                ExchangeRate exchangeRate = viewModel.getExchangeRate().getValue();

                if (exchangeRate != null) {
                    org.bitcoinj.utils.ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(
                            Coin.COIN,
                            exchangeRate.getFiat()
                    );
                    final Fiat localValue = rate.coinToFiat(balance);
                    viewBalanceLocal.setVisibility(View.VISIBLE);
                    String currencySymbol = GenericUtils.INSTANCE.currencySymbol(exchangeRate.getCurrencyCode());
                    viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0, currencySymbol));
                    viewBalanceLocal.setAmount(localValue);
                } else {
                    viewBalanceLocal.setVisibility(View.INVISIBLE);
                }
            }
        } else {
            viewBalanceDash.setAmount(Coin.ZERO);
        }

        requireActivity().invalidateOptionsMenu();
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

    private void setNotificationCount() {
        int notificationCount = viewModel.getNotificationCount();
        if (!viewModel.getHasIdentity()) {
            notifications.setVisibility(View.GONE);
            notificationBell.setVisibility(View.GONE);
        } else if (notificationCount > 0) {
            notifications.setText(String.valueOf(notificationCount));
            notifications.setVisibility(View.VISIBLE);
            notificationBell.setVisibility(View.GONE);
            viewModel.reportContactRequestTime();
        } else if (notificationCount == 0) {
            notifications.setVisibility(View.GONE);
            notificationBell.setVisibility(View.VISIBLE);
        }
    }
}
