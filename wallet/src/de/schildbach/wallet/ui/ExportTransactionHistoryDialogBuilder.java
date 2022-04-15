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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.common.base.Charsets;

import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;

import javax.annotation.Nullable;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

/**
 * @author Eric Britten
 */
public abstract class ExportTransactionHistoryDialogBuilder extends BaseAlertDialogBuilder {
    private final Activity context;

    private final EditText viewDescription;

    private static final Logger log = LoggerFactory.getLogger(ExportTransactionHistoryDialogBuilder.class);

    public ExportTransactionHistoryDialogBuilder(final Activity context, final int titleResId, final int messageResId, final int dialogResId) {
        super(context);

        this.context = context;

        final LayoutInflater inflater = LayoutInflater.from(context);
        final View view = inflater.inflate(dialogResId, null);

        ((TextView) view.findViewById(R.id.report_issue_dialog_message)).setText(messageResId);

        viewDescription = view.findViewById(R.id.report_issue_dialog_description);
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


        setTitle(context.getString(titleResId));
        setView(view);
        setPositiveText(context.getString(R.string.report_transaction_history_export));
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
            final CharSequence walletDump = collectWalletDump();

            if (walletDump != null) {
                final File file = File.createTempFile("taxbit-transaction-history.", ".csv", reportDir);

                final Writer writer = new OutputStreamWriter(new FileOutputStream(file), Charsets.UTF_8);
                writer.write(walletDump.toString());
                writer.close();

                attachments.add(
                        FileProvider.getUriForFile(context, context.getPackageName() + ".file_attachment", file));
            }
        } catch (final IOException x) {
            log.info("problem writing attachment", x);
        }

        startSend(subject(), text, attachments);
    }

    public static ExportTransactionHistoryDialogBuilder createExportTransactionDialog(final Activity context,
                                                                                      final WalletApplication application) {
        final ExportTransactionHistoryDialogBuilder dialog = new ExportTransactionHistoryDialogBuilder(context,
                R.string.report_transaction_history_title, R.string.report_transaction_history_message,
                R.layout.export_transaction_history_dialog) {
            @Override
            protected CharSequence subject() {
                return Constants.REPORT_SUBJECT_BEGIN + context.getString(R.string.report_transaction_history_title);
            }

            @Override
            protected CharSequence collectWalletDump() {
                return WalletUtils.getTransactionHistory(application.getWallet());
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

        if (subject != null)
            intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(Intent.EXTRA_TEXT, text);

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.report_transaction_history_mail_intent_chooser)));
            log.info("invoked chooser for exporting transaction history");
        } catch (final Exception x) {
            Toast.makeText(context, R.string.report_transaction_history_dialog_mail_intent_failed, Toast.LENGTH_LONG).show();
            log.error("export transaction history failed", x);
        }
    }

    @Nullable
    protected abstract CharSequence subject();

    @Nullable
    protected CharSequence collectWalletDump() throws IOException {
        return null;
    }

    private void imitateUserInteraction() {
        context.onUserInteraction();
    }
}
