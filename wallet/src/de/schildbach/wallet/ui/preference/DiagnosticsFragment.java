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

package de.schildbach.wallet.ui.preference;

import java.io.IOException;
import java.util.Locale;

import org.bitcoinj.crypto.DeterministicKey;
import org.dash.wallet.common.services.LockScreenBroadcaster;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.ReportIssueDialogBuilder;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.Qr;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import javax.inject.Inject;


/**
 * @author Andreas Schildbach
 * Extending from PreferenceFragment doesn't allow us to get the lifecycleowner
 */
@AndroidEntryPoint
public final class DiagnosticsFragment extends PreferenceFragmentCompat {
	private Activity activity;
	private WalletApplication application;
	private AlertDialog alertDialog;
	private static final String PREFS_KEY_REPORT_ISSUE = "report_issue";
	private static final String PREFS_KEY_INITIATE_RESET = "initiate_reset";
	private static final String PREFS_KEY_EXTENDED_PUBLIC_KEY = "extended_public_key";

	private static final Logger log = LoggerFactory.getLogger(DiagnosticsFragment.class);

	@Inject
	LockScreenBroadcaster lockScreenBroadcaster;

	@Override
    public void onAttach(final Activity activity) {
		super.onAttach(activity);

		this.activity = activity;
		this.application = (WalletApplication) activity.getApplication();
	}

	@Override
	public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		setPreferencesFromResource(R.xml.preference_diagnostics, rootKey);
	}

	@Override
	public void onViewCreated(@NonNull @NotNull View view, @Nullable @org.jetbrains.annotations.Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		lockScreenBroadcaster.getActivatingLockScreen().observe(getViewLifecycleOwner(), unused -> {
			if (alertDialog != null) {
				alertDialog.dismiss();
			}
		});
	}

	@Override
	public boolean onPreferenceTreeClick(Preference preference) {
		final String key = preference.getKey();

		if (PREFS_KEY_REPORT_ISSUE.equals(key)) {
			handleReportIssue();
			return true;
		} else if (PREFS_KEY_INITIATE_RESET.equals(key)) {
			handleInitiateReset();
			return true;
		} else if (PREFS_KEY_EXTENDED_PUBLIC_KEY.equals(key)) {
			handleExtendedPublicKey();
			return true;
		}

		return false;
	}

	private void handleReportIssue()
	{
		final ReportIssueDialogBuilder dialog = new ReportIssueDialogBuilder(activity, R.string.report_issue_dialog_title_issue,
				R.string.report_issue_dialog_message_issue)
		{
			@Override
			protected CharSequence subject()
			{
				return Constants.REPORT_SUBJECT_ISSUE + " " + application.packageInfo().versionName;
			}

			@Override
			protected CharSequence collectApplicationInfo() throws IOException
			{
				final StringBuilder applicationInfo = new StringBuilder();
				CrashReporter.appendApplicationInfo(applicationInfo, application);
				return applicationInfo;
			}

			@Override
			protected CharSequence collectStackTrace()
			{
				return null;
			}

			@Override
			protected CharSequence collectDeviceInfo() throws IOException
			{
				final StringBuilder deviceInfo = new StringBuilder();
				CrashReporter.appendDeviceInfo(deviceInfo, activity);
				return deviceInfo;
			}

			@Override
			protected CharSequence collectWalletDump()
			{
				return application.getWallet().toString(false, true, true, null);
			}
		};
		alertDialog = dialog.buildAlertDialog();
		alertDialog.show();
	}
    private void handleInitiateReset() {
		BaseAlertDialogBuilder baseAlertDialogBuilder = new BaseAlertDialogBuilder(requireActivity());
		baseAlertDialogBuilder.setTitle(getString(R.string.preferences_initiate_reset_title));
		baseAlertDialogBuilder.setMessage(getString(R.string.preferences_initiate_reset_dialog_message));
		baseAlertDialogBuilder.setPositiveText(getString(R.string.preferences_initiate_reset_dialog_positive));
		baseAlertDialogBuilder.setPositiveAction(
				() -> {
					log.info("manually initiated blockchain reset");
					application.resetBlockchain();
					activity.finish(); // TODO doesn't fully finish prefs on single pane layouts
					return Unit.INSTANCE;
				}
		);
		baseAlertDialogBuilder.setNegativeText(getString(R.string.button_dismiss));
		alertDialog = baseAlertDialogBuilder.buildAlertDialog();
		alertDialog.show();
	}

    private void handleExtendedPublicKey() {
		final DeterministicKey extendedKey = application.getWallet().getWatchingKey();
        final String xpub = String.format(Locale.US, "%s?c=%d&h=bip32",
                extendedKey.serializePubB58(Constants.NETWORK_PARAMETERS), extendedKey.getCreationTimeSeconds());
		showExtendedPublicKeyDialog(xpub);
	}

	private void showExtendedPublicKeyDialog(final String xpub) {
		final View view = LayoutInflater.from(activity).inflate(R.layout.extended_public_key_dialog, null);

		final BitmapDrawable bitmap = new BitmapDrawable(getResources(), Qr.bitmap(xpub));
		bitmap.setFilterBitmap(false);
		final ImageView imageView = (ImageView) view.findViewById(R.id.extended_public_key_dialog_image);
		imageView.setImageDrawable(bitmap);
		BaseAlertDialogBuilder baseAlertDialogBuilder = new BaseAlertDialogBuilder(requireActivity());
		baseAlertDialogBuilder.setView(view);
		baseAlertDialogBuilder.setNegativeText(getString(R.string.button_dismiss));
		baseAlertDialogBuilder.setPositiveText(getString(R.string.button_share));
		baseAlertDialogBuilder.setPositiveAction(
				() -> {
					createAndLaunchShareIntent(xpub);
					return Unit.INSTANCE;
				}
		);
		alertDialog = baseAlertDialogBuilder.buildAlertDialog();
		alertDialog.show();
	}

	private void createAndLaunchShareIntent(String xpub) {
		final Intent intent = new Intent(Intent.ACTION_SEND);
		intent.setType("text/plain");
		intent.putExtra(Intent.EXTRA_TEXT, xpub);
		intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.extended_public_key_fragment_title));
		startActivity(Intent.createChooser(intent, getString(R.string.extended_public_key_fragment_share)));
		log.info("xpub shared via intent: {}", xpub);
	}
}
