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

import android.content.Context;
import androidx.core.content.ContextCompat;
import android.util.AttributeSet;

import org.dash.wallet.common.ui.CurrencyTextView;

import de.schildbach.wallet_test.R;

public class ToolbarCurrencyTextView extends CurrencyTextView
{

    public ToolbarCurrencyTextView(final Context context)
    {
        super(context);
    }

    public ToolbarCurrencyTextView(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate()
    {
        super.onFinishInflate();

        setPrefixColor(ContextCompat.getColor(getContext(), R.color.white));
    }

	/*@Override
	protected void setTextFormat(CharSequence text)
	{
        if (text == null)
        {
            setText(null);
        }
        else
        {
            String textStr = text.toString();
            if (textStr.contains(MonetaryFormat.CODE_UBTC))
            {
                textStr = textStr.replace(MonetaryFormat.CODE_UBTC, "");
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.balance_prefix_micro, 0, 0, 0);
            }
            else if (textStr.contains(MonetaryFormat.CODE_MBTC))
            {
                textStr = textStr.replace(MonetaryFormat.CODE_MBTC, "");
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.balance_prefix_milli, 0, 0, 0);
            }
            else if (textStr.contains(MonetaryFormat.CODE_BTC))
            {
                textStr = textStr.replace(MonetaryFormat.CODE_BTC, "");
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.balance_prefix, 0, 0, 0);
            }
            setText(textStr);
        }
	}*/
}
