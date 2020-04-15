/*
 * Copyright 2018-present the original author or authors.
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

package de.schildbach.wallet.util;

import android.content.Context;
import androidx.annotation.Nullable;
import android.text.format.DateUtils;

import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class BlockchainStateUtils {

    private static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;

    @Nullable
    public static String getSyncStateString(BlockchainState blockchainState, Context context) {
        if (blockchainState == null || blockchainState.getBestChainDate() == null) {
            return null;
        }

        final long blockchainLag = System.currentTimeMillis() - blockchainState.getBestChainDate().getTime();
        final boolean blockchainUptodate = blockchainLag < BLOCKCHAIN_UPTODATE_THRESHOLD_MS;
        final boolean noImpediments = blockchainState.getImpediments().isEmpty();

        if (!blockchainUptodate || blockchainState.getReplaying()) {
            String progressMessage;
            final String downloading = context.getString(noImpediments ? R.string.blockchain_state_progress_downloading
                    : R.string.blockchain_state_progress_stalled);

            if (blockchainLag < 2 * DateUtils.DAY_IN_MILLIS) {
                final long hours = blockchainLag / DateUtils.HOUR_IN_MILLIS;
                progressMessage = context.getString(R.string.blockchain_state_progress_hours, downloading, hours);
            } else if (blockchainLag < 2 * DateUtils.WEEK_IN_MILLIS) {
                final long days = blockchainLag / DateUtils.DAY_IN_MILLIS;
                progressMessage = context.getString(R.string.blockchain_state_progress_days, downloading, days);
            } else if (blockchainLag < 90 * DateUtils.DAY_IN_MILLIS) {
                final long weeks = blockchainLag / DateUtils.WEEK_IN_MILLIS;
                progressMessage = context.getString(R.string.blockchain_state_progress_weeks, downloading, weeks);
            } else {
                final long months = blockchainLag / (30 * DateUtils.DAY_IN_MILLIS);
                progressMessage = context.getString(R.string.blockchain_state_progress_months, downloading, months);
            }

            return progressMessage;
        } else {
            return null;
        }
    }

}