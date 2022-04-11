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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;

import javax.annotation.Nullable;

import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * @author Andreas Schildbach
 */
public abstract class ReportIssueDialogBuilder extends BaseAlertDialogBuilder {
    private final Activity context;

    private EditText viewDescription;
    private CheckBox viewCollectDeviceInfo;
    private CheckBox viewCollectInstalledPackages;
    private CheckBox viewCollectApplicationLog;
    private CheckBox viewCollectWalletDump;

    private static final Logger log = LoggerFactory.getLogger(ReportIssueDialogBuilder.class);

    public ReportIssueDialogBuilder(final Activity context, final int titleResId, final int messageResId) {
        super(context);

        this.context = context;

        final LayoutInflater inflater = LayoutInflater.from(context);
        final View view = inflater.inflate(R.layout.report_issue_dialog, null);

        ((TextView) view.findViewById(R.id.report_issue_dialog_message)).setText(messageResId);

        viewDescription = (EditText) view.findViewById(R.id.report_issue_dialog_description);
        viewDescription.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                imitateUserInteraction();
            }
        });

        viewCollectDeviceInfo = (CheckBox) view.findViewById(R.id.report_issue_dialog_collect_device_info);
        viewCollectInstalledPackages = (CheckBox) view
                .findViewById(R.id.report_issue_dialog_collect_installed_packages);
        viewCollectApplicationLog = (CheckBox) view.findViewById(R.id.report_issue_dialog_collect_application_log);
        viewCollectWalletDump = (CheckBox) view.findViewById(R.id.report_issue_dialog_collect_wallet_dump);

        setTitle(context.getString(titleResId));
        setView(view);
        setPositiveText(context.getString((R.string.report_issue_dialog_report)));
        setPositiveAction(
                () -> {
                    positiveBtnClickListener();
                    return Unit.INSTANCE;
                }
        );
        setNegativeText(context.getString(R.string.button_cancel));
    }

    private void positiveBtnClickListener() {
        final StringBuilder text = new StringBuilder();
        final ArrayList<Uri> attachments = new ArrayList<Uri>();
        final File cacheDir = context.getCacheDir();
        final File reportDir = new File(cacheDir, "report");
        reportDir.mkdir();

        text.append(viewDescription.getText()).append('\n');
        imitateUserInteraction();

        try {
            final CharSequence contextualData = collectContextualData();
            if (contextualData != null) {
                text.append("\n\n\n=== contextual data ===\n\n");
                text.append(contextualData);
            }
        } catch (final IOException x) {
            text.append(x.toString()).append('\n');
        }

        try {
            text.append("\n\n\n=== application info ===\n\n");

            final CharSequence applicationInfo = collectApplicationInfo();

            text.append(applicationInfo);
        } catch (final IOException x) {
            text.append(x.toString()).append('\n');
        }

        try {
            final CharSequence stackTrace = collectStackTrace();

            if (stackTrace != null) {
                text.append("\n\n\n=== stack trace ===\n\n");
                text.append(stackTrace);
            }
        } catch (final IOException x) {
            text.append("\n\n\n=== stack trace ===\n\n");
            text.append(x.toString()).append('\n');
        }

        if (viewCollectDeviceInfo.isChecked()) {
            try {
                text.append("\n\n\n=== device info ===\n\n");

                final CharSequence deviceInfo = collectDeviceInfo();

                text.append(deviceInfo);
            } catch (final IOException x) {
                text.append(x.toString()).append('\n');
            }
        }

        if (viewCollectInstalledPackages.isChecked()) {
            try {
                text.append("\n\n\n=== installed packages ===\n\n");
                CrashReporter.appendInstalledPackages(text, context);
            } catch (final IOException x) {
                text.append(x.toString()).append('\n');
            }
        }

        if (viewCollectApplicationLog.isChecked()) {
            final File logDir = new File(context.getFilesDir(), "log");
            if (logDir.exists())
                for (final File logFile : logDir.listFiles())
                    if (logFile.isFile() && logFile.length() > 0)
                        attachments.add(FileProvider.getUriForFile(context,
                                context.getPackageName() + ".file_attachment", logFile));
        }

        if (viewCollectWalletDump.isChecked()) {
            try {
                final CharSequence walletDump = collectWalletDump();

                if (walletDump != null) {
                    final File file = File.createTempFile("wallet-dump.", ".txt", reportDir);

                    final Writer writer = new OutputStreamWriter(new FileOutputStream(file), Charsets.UTF_8);
                    writer.write(walletDump.toString());
                    writer.close();

                    attachments.add(
                            FileProvider.getUriForFile(context, context.getPackageName() + ".file_attachment", file));
                }
            } catch (final IOException x) {
                log.info("problem writing attachment", x);
            }
        }

        try {
            final File savedBackgroundTraces = File.createTempFile("background-traces.", ".txt", reportDir);
            if (CrashReporter.collectSavedBackgroundTraces(savedBackgroundTraces)) {
                attachments.add(FileProvider.getUriForFile(context, context.getPackageName() + ".file_attachment",
                        savedBackgroundTraces));
            }
            savedBackgroundTraces.deleteOnExit();
        } catch (final IOException x) {
            log.info("problem writing attachment", x);
        }

        text.append("\n\nPUT ADDITIONAL COMMENTS TO THE TOP. DOWN HERE NOBODY WILL NOTICE.");

        startSend(subject(), text, attachments);
    }

    public static ReportIssueDialogBuilder createReportIssueDialog(final Activity context,
                                                                   final WalletApplication application) {
        final ReportIssueDialogBuilder dialog = new ReportIssueDialogBuilder(context,
                R.string.report_issue_dialog_title_issue, R.string.report_issue_dialog_message_issue) {
            @Override
            protected CharSequence subject() {
                return Constants.REPORT_SUBJECT_BEGIN + application.packageInfo().versionName + " "
                        + Constants.REPORT_SUBJECT_ISSUE;
            }

            @Override
            protected CharSequence collectApplicationInfo() throws IOException {
                final StringBuilder applicationInfo = new StringBuilder();
                CrashReporter.appendApplicationInfo(applicationInfo, application);
                return applicationInfo;
            }

            @Override
            protected CharSequence collectDeviceInfo() throws IOException {
                final StringBuilder deviceInfo = new StringBuilder();
                CrashReporter.appendDeviceInfo(deviceInfo, context);
                return deviceInfo;
            }

            @Override
            protected CharSequence collectWalletDump() {
                return application.getWallet().toString(false, true,
                        true, null);
            }
        };
        return dialog;
    }

    private void startSend(final CharSequence subject, final CharSequence text, final ArrayList<Uri> attachments) {
        final Intent intent;

        if (attachments.size() == 0) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.setType("message/rfc822");
        } else if (attachments.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, attachments.get(0));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("text/plain");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments);
        }

        intent.putExtra(Intent.EXTRA_EMAIL, new String[] { Constants.REPORT_EMAIL });
        if (subject != null)
            intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(Intent.EXTRA_TEXT, text);

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.report_issue_dialog_mail_intent_chooser)));
            log.info("invoked chooser for sending issue report");
        } catch (final Exception x) {
            Toast.makeText(context, R.string.report_issue_dialog_mail_intent_failed, Toast.LENGTH_LONG).show();
            log.error("report issue failed", x);
        }
    }

    @Nullable
    protected abstract CharSequence subject();

    @Nullable
    protected CharSequence collectApplicationInfo() throws IOException {
        return null;
    }

    @Nullable
    protected CharSequence collectStackTrace() throws IOException {
        return null;
    }

    @Nullable
    protected CharSequence collectDeviceInfo() throws IOException {
        return null;
    }

    @Nullable
    protected CharSequence collectContextualData() throws IOException {
        return null;
    }

    @Nullable
    protected CharSequence collectWalletDump() throws IOException {
        return null;
    }

    private void imitateUserInteraction() {
        context.onUserInteraction();
    }
}
