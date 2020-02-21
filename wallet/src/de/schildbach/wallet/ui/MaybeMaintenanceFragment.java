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

package de.schildbach.wallet.ui;

import android.app.Activity;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.common.util.concurrent.ListenableFuture;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.DeterministicUpgradeRequiresPassword;
import org.bitcoinj.wallet.Wallet;

import java.util.List;

import de.schildbach.wallet.WalletApplication;

/**
 * @author Andreas Schildbach
 */
//TODO: This class is not needed and will be removed in a future PR
public class MaybeMaintenanceFragment extends Fragment {
    private static final String FRAGMENT_TAG = MaybeMaintenanceFragment.class.getName();

    public static void add(final FragmentManager fm) {
        Fragment fragment = fm.findFragmentByTag(FRAGMENT_TAG);
        if (fragment == null) {
            fragment = new MaybeMaintenanceFragment();
            fm.beginTransaction().add(fragment, FRAGMENT_TAG).commit();
        }
    }

    private Wallet wallet;
    private boolean dialogWasShown = false;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        final WalletApplication application = ((AbstractWalletActivity) activity).getWalletApplication();
        this.wallet = application.getWallet();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
    }

    private boolean maintenanceRecommended() {
        try {
            final ListenableFuture<List<Transaction>> result = wallet.doMaintenance(null, false);
            return !result.get().isEmpty();
        } catch (final DeterministicUpgradeRequiresPassword x) {
            return true;
        } catch (final Exception x) {
            throw new RuntimeException(x);
        }
    }
}
