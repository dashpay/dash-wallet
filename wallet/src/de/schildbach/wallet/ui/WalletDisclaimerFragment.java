/*
 * Copyright 2013-2015 the original author or authors.
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

import java.util.Set;

import javax.annotation.Nullable;

import org.dash.wallet.common.Configuration;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet_test.R;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * @author Andreas Schildbach
 */
public final class WalletDisclaimerFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private AbstractBindServiceActivity activity;
    private Configuration config;
    private LoaderManager loaderManager;

    private TextView messageView;
    private View closeSafetyDisclaimerView;

    private static final int ID_BLOCKCHAIN_STATE_LOADER = 0;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractBindServiceActivity) activity;
        final WalletApplication application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.loaderManager = getLoaderManager();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.wallet_disclaimer_fragment, container);

        closeSafetyDisclaimerView = view.findViewById(R.id.wallet_disclaimer_close);
        closeSafetyDisclaimerView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                config.setDisclaimerEnabled(false);
            }
        });
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        config.registerOnSharedPreferenceChangeListener(this);
        updateView();
    }

    @Override
    public void onPause() {
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);

        config.unregisterOnSharedPreferenceChangeListener(this);

        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_DISCLAIMER.equals(key))
            updateView();
    }

    private void updateView() {
        if (!isResumed())
            return;

        final boolean showDisclaimer = config.getDisclaimerEnabled();
        closeSafetyDisclaimerView.setVisibility(showDisclaimer ? View.VISIBLE : View.GONE);

        final SpannableStringBuilder text = new SpannableStringBuilder();
        if (showDisclaimer)
            text.append(Html.fromHtml(getString(R.string.wallet_disclaimer_fragment_remind_safety)));
        messageView.setText(text);

        final View view = getView();
        final ViewParent parent = view.getParent();
        final View fragment = parent instanceof FrameLayout ? (FrameLayout) parent : view;
        fragment.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    }

}
