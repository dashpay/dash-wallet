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

package org.dash.wallet.common.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import android.view.LayoutInflater;
import android.widget.TextView;

import org.dash.wallet.common.R;

/**
 * @author Andreas Schildbach
 */
public class DialogBuilder extends AlertDialog.Builder {

    public static DialogBuilder warn(final Context context, final int titleResId) {
        final DialogBuilder builder = new DialogBuilder(context);
        builder.setIcon(R.drawable.ic_warning);
        builder.setTitle(titleResId);
        return builder;
    }

    public DialogBuilder(final Context context) {
        super(context);
    }

    @SuppressLint("InflateParams")
    @Override
    public AlertDialog.Builder setTitle(int titleId) {
        return this.setTitle(getContext().getString(titleId));
    }

    @Override
    public AlertDialog.Builder setTitle(@androidx.annotation.Nullable CharSequence title) {
        TextView titleTextView = (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_title, null);
        titleTextView.setText(title);
        return this.setCustomTitle(titleTextView);
    }

    @Override
    public DialogBuilder setMessage(final int messageResId) {
        return this.setMessage(this.getContext().getString(messageResId));
    }

    @Override
    public DialogBuilder setMessage(final CharSequence message) {
        TextView messageTextView = (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_message, null);
        messageTextView.setText(message);
        this.setView(messageTextView);
        return this;
    }

    public DialogBuilder singleDismissButton(@Nullable final OnClickListener dismissListener) {
        setNeutralButton(android.R.string.cancel, dismissListener);

        return this;
    }
}
