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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What [PlatformAddressSyncService] needs from the SDK, as a seam so the
 * start/stop fencing is testable on the host JVM.
 */
internal interface PlatformAddressSyncSource {
    /**
     * Whether the loop may run now: the cutover is committed, this process's
     * bind pass established the app wallet, and the live SDK runtime has
     * exactly that one wallet loaded. Never starts the SDK. Throws when the
     * cutover state cannot be read (the caller then does not start).
     */
    suspend fun ready(): Boolean

    suspend fun isRunning(): Boolean

    suspend fun start()

    /** Stop the loop of the live runtime; a no-op when no runtime is up. */
    suspend fun stop()
}

private class DashSdkPlatformAddressSyncSource(
    private val sdkService: DashSdkService,
    private val sdkWalletBinder: SdkWalletBinder,
    private val dashPayConfig: DashPayConfig
) : PlatformAddressSyncSource {

    /** The live, open manager, or null. Never boots the SDK. */
    private fun liveManager() = sdkService.walletManagerOrNull()?.takeUnless { it.isClosed }

    override suspend fun ready(): Boolean {
        val cutoverCommitted = dashPayConfig.cutoverCommittedOrThrow()
        if (!cutoverCommitted || !sdkWalletBinder.bindEstablished.value) return false
        return liveManager()?.wallets?.value?.size == 1
    }

    override suspend fun isRunning(): Boolean = liveManager()?.isPlatformAddressSyncRunning() == true

    override suspend fun start() {
        checkNotNull(liveManager()) { "SDK wallet manager not running" }.startPlatformAddressSync()
    }

    override suspend fun stop() {
        liveManager()?.stopPlatformAddressSync()
    }
}

/**
 * Starts and stops the SDK's Platform-address (BLAST) balance sync loop.
 *
 * Without it the wallet only knew the Platform-address balances it wrote
 * itself (funding / transfer changesets): credits another wallet sent to one
 * of our addresses never appeared. The loop is Rust-owned; each pass queries
 * every loaded wallet's addresses incrementally, updates the wallet's
 * in-memory balances (what `ManagedPlatformWallet.addressesWithBalances()`
 * reads) and persists them through the SDK's persistence handler
 * (`platform_addresses` rows, `platform_addresses_sync_states` watermark). A
 * failed pass changes nothing: the last persisted balance stays.
 *
 * Cadence is the SDK default (15 s, Rust `DEFAULT_SYNC_INTERVAL_SECS`; the
 * Kotlin SDK exposes no setter for this loop). It runs only while the
 * blockchain service runs: [PlatformSyncService] starts it from its engine
 * kick after the bind pass and stops it with the other engines on teardown
 * and before a wallet wipe (whose SDK close also stops it natively, and whose
 * removal cascade deletes the wallet's rows and watermark).
 *
 * Not `resetPlatformAddressSyncState()`: the SDK reserves that for an
 * explicit "clear and resync" of Platform balances. A Core rescan does not
 * touch Platform-address state, and a wipe removes the wallet instead.
 *
 * Fencing: [stop] bumps [stopGeneration] before it waits for an in-flight
 * start, and a start refuses (or undoes itself) when the generation it was
 * issued under has moved, so a stop can never be overtaken by a start that
 * was decided before it.
 */
@Singleton
class PlatformAddressSyncService internal constructor(
    private val source: PlatformAddressSyncSource,
    private val stopLockTimeoutMs: Long = STOP_LOCK_TIMEOUT_MS
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        sdkWalletBinder: SdkWalletBinder,
        dashPayConfig: DashPayConfig
    ) : this(DashSdkPlatformAddressSyncSource(sdkService, sdkWalletBinder, dashPayConfig))

    private val log = LoggerFactory.getLogger(PlatformAddressSyncService::class.java)
    private val mutex = Mutex()
    private val generation = AtomicLong(0)

    /** Sample before deciding to start; pass it to [startIfReady]. */
    fun stopGeneration(): Long = generation.get()

    /**
     * Start the loop if [PlatformAddressSyncSource.ready] and no [stop] has
     * happened since [expectedStopGeneration] was sampled. Idempotent. Never
     * throws except [CancellationException]; returns whether the loop is
     * running afterwards.
     */
    suspend fun startIfReady(expectedStopGeneration: Long): Boolean = try {
        mutex.withLock {
            fun superseded() = generation.get() != expectedStopGeneration
            when {
                superseded() -> {
                    log.info("Platform address sync not started: an engine stop arrived since the start was decided")
                    false
                }
                !source.ready() -> {
                    log.info("Platform address sync not started: the SDK wallet is not bound yet")
                    false
                }
                source.isRunning() -> true
                else -> {
                    source.start()
                    if (superseded()) {
                        // The stop that moved the generation timed out on the
                        // lock and could not reach the loop started here.
                        log.info("Platform address sync start superseded by a stop; stopping it again")
                        source.stop()
                        false
                    } else {
                        log.info("Platform address sync started")
                        true
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Platform address sync start failed; the next engine kick retries", e)
        false
    }

    /**
     * Stop the loop. Best-effort and failure-contained; never throws except
     * [CancellationException]. Waits (bounded) for an in-flight start so the
     * loop it starts is stopped too; past the bound that start stops itself.
     */
    suspend fun stop() {
        generation.incrementAndGet()
        try {
            val stopped = withTimeoutOrNull(stopLockTimeoutMs) { mutex.withLock { source.stop() } } != null
            if (!stopped) {
                log.warn("Platform address sync stop: a start is still in flight; it stops itself when it returns")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Platform address sync stop failed", e)
        }
    }

    private companion object {
        const val STOP_LOCK_TIMEOUT_MS = 10_000L
    }
}
