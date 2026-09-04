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
package de.schildbach.wallet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

/**
 * Whether the app has any activity in the STARTED state — i.e. the user can
 * see it.
 *
 * WHY THIS EXISTS RATHER THAN `ProcessLifecycleOwner`:
 * `wallet/AndroidManifest.xml` removes `androidx.startup.InitializationProvider`
 * with `tools:node="remove"` (a deliberate cold-start optimisation — that
 * provider runs before `Application.onCreate` on every launch, and removing it
 * also suppresses WorkManager's default initializer). That provider is what runs
 * `lifecycle-process`'s `ProcessLifecycleInitializer`, which is the only thing
 * that wires `ProcessLifecycleOwner` to activity transitions. Without it
 * `ProcessLifecycleOwner.get()` returns an owner that never leaves
 * `INITIALIZED`, so its `onStart`/`onStop` NEVER fire — silently, with no error.
 *
 * MO-995 evidence: `BlockchainServiceImpl` observed `ProcessLifecycleOwner` and
 * logged "App moved to foreground"/"...background" from it. Across a 27,000-line
 * field log with 36 service `onCreate`s, those lines appear **zero** times. So
 * `isAppInBackground` was permanently stuck at its initial value, and
 * `SdkBindRetryService.noteAppForeground()` — the app-foreground bind-retry
 * trigger — was dead code that had never once run.
 *
 * [WalletActivityTracker] is registered the plain way
 * (`Application.registerActivityLifecycleCallbacks`, WalletApplication:384) and
 * demonstrably works, and its [ActivitiesTracker] base already computes exactly
 * the first-started / last-stopped edges. So the signal is taken from there
 * instead — no androidx.startup, and no effect on any other library's
 * initializer.
 *
 * A plain object rather than an injected singleton: the producer is a
 * `registerActivityLifecycleCallbacks` callback constructed by
 * `WalletApplication` before Hilt is usable, and the consumers are services that
 * come and go. Reads are cheap and lock-free.
 */
object AppForegroundMonitor {
    private val log = LoggerFactory.getLogger(AppForegroundMonitor::class.java)

    private val _isForeground = MutableStateFlow(false)

    /** True while at least one activity is STARTED (visible to the user). */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** Convenience for the many call sites that only want the current value. */
    val isInBackground: Boolean get() = !_isForeground.value

    /** First activity started — the app became visible. */
    fun noteForeground() {
        if (_isForeground.compareAndSet(expect = false, update = true)) {
            log.info("App moved to foreground")
        }
    }

    /** Last activity stopped — the app went away. */
    fun noteBackground() {
        if (_isForeground.compareAndSet(expect = true, update = false)) {
            log.info("App moved to background")
        }
    }
}
