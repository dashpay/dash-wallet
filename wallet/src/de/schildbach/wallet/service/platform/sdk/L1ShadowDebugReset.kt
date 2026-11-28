/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.service.platform.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import de.schildbach.wallet_test.BuildConfig
import org.slf4j.LoggerFactory

/**
 * DEBUG-BUILDS-ONLY adb trigger for the two L1 shadow recovery levels:
 *
 * ```
 * # filesystem-level hard reset (resetShadowState(hard = true)):
 * adb shell am broadcast -a hashengineering.darkcoin.wallet_test.action.RESET_L1_SHADOW
 *
 * # DEFINITIVE recovery — full SDK-wallet re-creation
 * # (L1ShadowSyncService.recoverByRecreatingWallet):
 * adb shell am broadcast -a hashengineering.darkcoin.wallet_test.action.RESET_L1_SHADOW --ez recreate true
 * ```
 *
 * Registered DYNAMICALLY from [de.schildbach.wallet.WalletApplication]
 * (not the manifest) and ONLY when [BuildConfig.DEBUG] — the entire code
 * path no-ops in release builds, so no receiver, action, or attack
 * surface ships to production. The receiver must be exported
 * ([ContextCompat.RECEIVER_EXPORTED]) because `adb shell am broadcast`
 * runs as the `shell` user, which cannot deliver to a non-exported
 * receiver; the debug-only registration is the guard. Both triggers are
 * fire-and-forget ([L1ShadowSyncService.hardResetInBackground] /
 * [L1ShadowSyncService.recreateWalletInBackground]) and safely no-op when
 * the shadow sync isn't running (reset) / no SDK wallet is bound
 * (recreate). Either path touches ONLY SDK-side state — the dashj wallet
 * is untouched (see the recovery KDoc's safety contract).
 */
object L1ShadowDebugReset {
    /** Deliberately flavor-independent (a fixed string, not `applicationId`-derived). */
    const val ACTION_RESET_L1_SHADOW = "hashengineering.darkcoin.wallet_test.action.RESET_L1_SHADOW"

    /** Boolean extra: true routes to the full SDK-wallet re-creation recovery. */
    const val EXTRA_RECREATE = "recreate"

    private val log = LoggerFactory.getLogger(L1ShadowDebugReset::class.java)

    /**
     * Register the debug reset receiver; a provable no-op unless
     * [BuildConfig.DEBUG].
     */
    @JvmStatic
    fun registerIfDebug(context: Context, service: L1ShadowSyncService) {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getBooleanExtra(EXTRA_RECREATE, false)) {
                    log.warn(
                        "debug broadcast {} (recreate=true) received — launching a " +
                            "fire-and-forget FULL SDK-WALLET RE-CREATION (removeWallet cascade " +
                            "+ dataDir wipe + rebind + rescan; SDK-side state only)",
                        intent.action
                    )
                    service.recreateWalletInBackground()
                    return
                }
                log.warn(
                    "debug broadcast {} received — launching a fire-and-forget L1 shadow " +
                        "HARD reset (filesystem-level dataDir wipe + Room row purge + rescan)",
                    intent.action
                )
                service.hardResetInBackground()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_RESET_L1_SHADOW),
            ContextCompat.RECEIVER_EXPORTED
        )
        log.info(
            "L1 shadow debug reset receiver registered (debug build only; action={})",
            ACTION_RESET_L1_SHADOW
        )
    }
}
