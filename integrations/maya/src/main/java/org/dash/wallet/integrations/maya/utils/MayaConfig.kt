/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.integrations.maya.utils

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class MayaConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider
) : BaseConfig(context, PREFERENCES_NAME, walletDataProvider) {
    companion object {
        const val PREFERENCES_NAME = "maya"

        val BACKGROUND_ERROR = stringPreferencesKey("error")
        val expirationDuration = TimeUnit.DAYS.toMillis(1)
        val EXCHANGE_RATE_LAST_UPDATE = longPreferencesKey("exchange_rate_last_update")
        val EXCHANGE_RATE_VALUE = doublePreferencesKey("exchange_rate_value")
        val EXCHANGE_RATE_CURRENCY_CODE = stringPreferencesKey("exchange_rate_currency_code")

        /**
         * Stores the [SwapBackend] name. Read once at app start by the Hilt
         * `SwapProvider` provider; changes take effect on the next launch.
         */
        val SWAP_BACKEND = stringPreferencesKey("swap_backend")

        /**
         * Cached SwapKit coin-list snapshot (JSON) for the SELL flow (DASH -> crypto): the last
         * published pool list + Maya/NEAR classification + preferred-route map + timestamps.
         * Hydrated on cold start so the currency picker renders instantly (stale-while-revalidate);
         * the aggregator refreshes in the background and overwrites it. The BUY flow caches its
         * (Maya-only-excluded, NEAR-routed) list separately in [SWAPKIT_POOL_SNAPSHOT_BUY] so the
         * two directions don't overwrite each other's cached metadata. See
         * [org.dash.wallet.integrations.maya.swapkit.SwapKitApiAggregator].
         */
        val SWAPKIT_POOL_SNAPSHOT = stringPreferencesKey("swapkit_pool_snapshot")

        /**
         * Cached SwapKit coin-list snapshot (JSON) for the BUY flow (crypto -> DASH). Mirror of
         * [SWAPKIT_POOL_SNAPSHOT] but for the BUY direction, which excludes Maya-only assets and
         * routes everything via NEAR (no resolved preferred routes / Maya halt status). Kept under
         * a separate key so a BUY refresh never clobbers the richer SELL snapshot, and vice versa.
         */
        val SWAPKIT_POOL_SNAPSHOT_BUY = stringPreferencesKey("swapkit_pool_snapshot_buy")
    }
}
