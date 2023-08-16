/*
 * Copyright 2012-2015 the original author or authors.
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

package de.schildbach.wallet.ui.rates;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.ui.AbstractBindServiceActivity;
import de.schildbach.wallet_test.R;

import android.os.Bundle;

/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
public class ExchangeRatesActivity extends AbstractBindServiceActivity {

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.exchange_rates_content);

        if(savedInstanceState == null) {
//            String currencyCode = getIntent().getStringExtra(ExchangeRatesFragment.ARG_CURRENCY_CODE);
//            getSupportFragmentManager().beginTransaction()
//                    .replace(R.id.container, ExchangeRatesFragment.newInstance(currencyCode))
//                    .commitNow();
        }
    }

    @Override
    public void finish() {
        super.finishAndRemoveTask();
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left);
    }

}
