/*
 * Copyright 2011-2015 the original author or authors.
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

package org.dash.wallet.common.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;

import androidx.core.content.res.ResourcesCompat;

import org.dash.wallet.common.R;

import java.util.Currency;

/**
 * @author Andreas Schildbach
 */
public class GenericUtils {

    public static boolean startsWithIgnoreCase(final String string, final String prefix) {
        return string.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static String currencySymbol(final String currencyCode) {
        try {
            final Currency currency = Currency.getInstance(currencyCode);
            return currency.getSymbol();
        } catch (final IllegalArgumentException x) {
            return currencyCode;
        }
    }

    public static Spannable appendDashSymbol(Context context, CharSequence text, boolean spaceBefore, boolean spaceAfter, float scale) {
        return insertDashSymbol(context, text, text.length(), spaceBefore, spaceAfter, scale);
    }

    public static Spannable insertDashSymbol(Context context, CharSequence text, int position, boolean spaceBefore, boolean spaceAfter, float scale) {

        Drawable drawableDash = ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_dash_d_black, null);
        if (drawableDash == null) {
            return null;
        }
        int size = (int) (scale * 32);
        drawableDash.setBounds(0, 0, size, size);
        ImageSpan dashSymbol = new ImageSpan(drawableDash, ImageSpan.ALIGN_BASELINE);

        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        if (spaceBefore) {
            builder.insert(position++, " ");
        }
        builder.insert(position, " ");
        if (spaceAfter) {
            builder.insert(position + 1, " ");
        }
        builder.setSpan(dashSymbol, position, position + 1, 0);

        return builder;
    }
}
