/*
 * Copyright 2015-present the original author or authors.
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

package de.schildbach.wallet.ui.widget;

import android.content.Context;
import androidx.core.content.res.ResourcesCompat;
import android.util.AttributeSet;

import de.schildbach.wallet_test.R;

public class MontserratCheckBox extends androidx.appcompat.widget.AppCompatCheckBox {

    public MontserratCheckBox(Context context) {
        super(context);
    }

    public MontserratCheckBox(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MontserratCheckBox(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        setTypeface(ResourcesCompat.getFont(getContext(), R.font.montserrat_medium));
        super.onFinishInflate();
    }

}
