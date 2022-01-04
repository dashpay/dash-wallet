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

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet_test.R;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.ListFragment;
import androidx.lifecycle.ViewModelProvider;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.dash.wallet.common.ui.LockScreenViewModel;


/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
public class FancyListFragment extends ListFragment {
    protected LockScreenViewModel lockScreenViewModel;
    protected AlertDialog alertDialog;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lockScreenViewModel = new ViewModelProvider(requireActivity()).get(LockScreenViewModel.class);
        lockScreenViewModel.getActivatingLockScreen().observe(this, unused -> alertDialog.dismiss());
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fancy_list_content, container, false);
    }

    @Override
    public void setEmptyText(final CharSequence text) {
        final TextView emptyView = (TextView) getView().findViewById(android.R.id.empty);
        emptyView.setText(text);
    }
}
