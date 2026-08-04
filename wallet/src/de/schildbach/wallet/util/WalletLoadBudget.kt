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

package de.schildbach.wallet.util

import org.slf4j.LoggerFactory

/**
 * TIME budget for the synchronous wallet load, the companion to the SIZE guard
 * in [WalletFileSizeGuard].
 *
 * The DashPay friend-key-chain crash loop proved a wallet can be small enough
 * to sail past every size check and still take MINUTES to parse (see
 * [FriendKeyChainLookahead]). `WalletProtobufSerializer.readWallet` cannot be
 * interrupted safely mid-parse — the wallet object would be half-built — so
 * this guard does not abort the load. It does the two things that are both
 * safe and sufficient to stop a slow load turning into an un-openable app:
 *
 *  1. it MARKS the over-budget launch ([StartupBreadcrumbs.STAGE_WALLET_LOAD_OVERBUDGET])
 *     from a watchdog thread, so the evidence survives even when the process is
 *     killed with no Java stack — the support report names the stage AND the
 *     elapsed time; and
 *  2. it ARMS the existing crash-loop breaker
 *     ([StartupBreadcrumbs.armSafeModeOnNextDeath]) so that if this launch does
 *     die, the VERY NEXT launch runs in safe mode and the app opens — instead
 *     of the user having to sit through two more full-length deaths first.
 *
 * A launch that goes over budget but still finishes normally costs nothing: the
 * breadcrumb trail ends with the survival marker and the armed counter is reset
 * by the next `StartupBreadcrumbs.init`.
 */
object WalletLoadBudget {
    private val log = LoggerFactory.getLogger(WalletLoadBudget::class.java)

    /**
     * How long a wallet load may take before the launch is treated as at risk.
     *
     * Grounded in the tester's log: the launches that died had been inside
     * `readWallet` for 20–50 s with no window on screen. A healthy load of even
     * a very large wallet is a few seconds; with the friend-chain lookahead
     * deferred the pathological case measured here parses in ~2 s. 20 s is
     * therefore comfortably above every legitimate load and below every observed
     * death.
     */
    const val DEFAULT_BUDGET_MS = 20_000L

    /**
     * An armed watchdog. [disarm] it when the guarded work finishes; call it
     * from a `finally` so an exceptional exit disarms too.
     */
    class Watchdog internal constructor(
        private val startedAtMs: Long,
        private val thread: Thread?
    ) {
        /** @return the elapsed time of the guarded work, in ms. */
        fun disarm(): Long {
            thread?.interrupt()
            return System.currentTimeMillis() - startedAtMs
        }
    }

    /**
     * Arm a daemon watchdog that runs [onOverBudget] once, on its own thread,
     * if the guarded work has not disarmed it within [budgetMs].
     *
     * Never throws: a diagnostic must not be able to take a launch down. If the
     * thread cannot be started the returned watchdog is simply inert.
     */
    @JvmStatic
    @JvmOverloads
    fun arm(budgetMs: Long = DEFAULT_BUDGET_MS, onOverBudget: Runnable): Watchdog {
        val startedAtMs = System.currentTimeMillis()
        return try {
            val thread = Thread({
                try {
                    Thread.sleep(budgetMs)
                } catch (interrupted: InterruptedException) {
                    return@Thread // disarmed in time — the common case
                }
                try {
                    onOverBudget.run()
                } catch (t: Throwable) {
                    log.error("wallet load budget handler failed", t)
                }
            }, "wallet-load-budget")
            thread.isDaemon = true
            thread.start()
            Watchdog(startedAtMs, thread)
        } catch (t: Throwable) {
            log.error("failed to arm the wallet load budget watchdog", t)
            Watchdog(startedAtMs, null)
        }
    }

    /** Whether an elapsed load blew its budget (pure, for tests and reporting). */
    @JvmStatic
    fun isOverBudget(elapsedMs: Long, budgetMs: Long = DEFAULT_BUDGET_MS): Boolean = elapsedMs >= budgetMs
}
