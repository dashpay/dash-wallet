package de.schildbach.wallet.util;

import android.content.Context;
import android.support.annotation.Nullable;
import android.text.format.DateUtils;

import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet_test.R;

public class BlockchainStateUtils {

    private static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;

    @Nullable
    public static String getSyncStateString(BlockchainState blockchainState, Context context) {
        final long blockchainLag = System.currentTimeMillis() - blockchainState.bestChainDate.getTime();
        final boolean blockchainUptodate = blockchainLag < BLOCKCHAIN_UPTODATE_THRESHOLD_MS;
        final boolean noImpediments = blockchainState.impediments.isEmpty();

        if (!(blockchainUptodate || !blockchainState.replaying)) {
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
