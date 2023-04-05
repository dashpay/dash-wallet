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

package org.dash.wallet.common.ui;

import static org.dash.wallet.common.util.Constants.PREFIX_ALMOST_EQUAL_TO;

import android.content.Context;
import android.graphics.Paint;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Monetary;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.MonetaryFormat;
import org.dash.wallet.common.util.Constants;
import org.dash.wallet.common.util.MonetarySpannable;

/**
 * @author Andreas Schildbach
 */
public class CurrencyTextView extends AppCompatTextView {
    private Monetary amount = null;
    private MonetaryFormat format = null;
    private boolean alwaysSigned = false;
    private RelativeSizeSpan prefixRelativeSizeSpan = null;
    private RelativeSizeSpan insignificantRelativeSizeSpan = null;
    private boolean applyMarkup = true;

    public CurrencyTextView(final Context context) {
        super(context);
    }
    public CurrencyTextView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAmount(final Monetary amount) {
        this.amount = amount;
        updateView();
    }

    public void setFormat(final MonetaryFormat format) {
        this.format = format.codeSeparator(Constants.CHAR_HAIR_SPACE);
        updateView();
    }

    public void setAlwaysSigned(final boolean alwaysSigned) {
        this.alwaysSigned = alwaysSigned;
        updateView();
    }

    public void setStrikeThru(final boolean strikeThru) {
        if (strikeThru)
            setPaintFlags(getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        else
            setPaintFlags(getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
    }

    public void setApplyMarkup(boolean applyMarkup) {
        this.applyMarkup = applyMarkup;
    }

    public void setInsignificantRelativeSize(final float insignificantRelativeSize) {
        if (insignificantRelativeSize != 1) {
            this.prefixRelativeSizeSpan = new RelativeSizeSpan(insignificantRelativeSize);
            this.insignificantRelativeSizeSpan = new RelativeSizeSpan(insignificantRelativeSize);
        } else {
            this.prefixRelativeSizeSpan = null;
            this.insignificantRelativeSizeSpan = null;
        }
    }

    public void setFiatAmount(Coin amount, ExchangeRate exchangeRate, MonetaryFormat format,
                              String exchangeCurrencyCode) {
        setAmount(null);  //clear the exchange rate first
        if (exchangeRate != null) {
            setFormat(format.code(0, exchangeCurrencyCode + " "));
            Coin absCoin = Coin.valueOf(Math.abs(amount.value));
            setAmount(exchangeRate.coinToFiat(absCoin));
        } else {
            setText(PREFIX_ALMOST_EQUAL_TO + exchangeCurrencyCode + " ----");
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setInsignificantRelativeSize(0.85f);
        setSingleLine();
    }

    private void updateView() {
        MonetarySpannable text;

        if (amount != null) {
            text = new MonetarySpannable(format, alwaysSigned, amount);
            if (applyMarkup) {
                text = text.applyMarkup(
                        new Object[] { prefixRelativeSizeSpan },
                        new Object[] { insignificantRelativeSizeSpan });
            }
        } else {
            text = null;
        }

        setText(text);
    }
}
