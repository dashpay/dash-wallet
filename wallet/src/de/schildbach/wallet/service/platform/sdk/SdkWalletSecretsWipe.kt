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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import org.dashfoundation.dashsdk.security.KeySecurityPolicy
import org.dashfoundation.dashsdk.security.WalletStorage
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two [WalletStorage] operations Reset Wallet needs, behind an app-side
 * seam so the erase decision is testable without the (final, Keystore-backed)
 * SDK class.
 */
interface SdkSecretsStore {
    /** Every entry name in the SDK's secrets DataStore. */
    suspend fun listEntryNames(): List<String>

    /** Clear the whole store (one atomic edit through the live DataStore). */
    suspend fun deleteAll()
}

/** How Reset Wallet disposed of the SDK's secrets DataStore. */
enum class SdkSecretsWipeOutcome(val erased: Boolean) {
    /**
     * The SDK wallet-removal cascade left no wallet secret behind (verified
     * entry by entry): the store is kept, so the device-scoped false-locked
     * Keystore defect record survives the reset (MO-1023).
     */
    VERIFIED_CLEAN_KEPT(true),

    /**
     * Wallet secrets were still present (or the store could not be listed):
     * the whole store was cleared through the live instance. The defect
     * record goes with it.
     */
    DELETED_ALL(true),

    /** No store instance could be opened; the file was raw-deleted and is confirmed gone. */
    RAW_DELETED(true),

    /**
     * The previous wallet's secrets may still be on disk. The wipe must not
     * report complete (its marker stays, and the next launch re-runs it).
     */
    FAILED(false)
}

/**
 * Reset Wallet's erase step for the Kotlin SDK's secrets DataStore
 * (`org.dashfoundation.wallet.secrets`), run AFTER the SDK wallet-removal
 * cascade (`L1ShadowSyncService.clearForWalletWipe`).
 *
 * The cascade is best-effort (it swallows removal failures, skips an
 * unbound or unloaded wallet), so it is not trusted: the store's contents
 * are checked instead. Every entry that is not a known device-scoped,
 * non-secret record ([DEVICE_SCOPED_ENTRY_NAMES]) counts as a wallet
 * secret — an allowlist, so an SDK key layout this code does not know about
 * fails closed (erased) rather than open (kept).
 */
object SdkWalletSecretsWipe {
    private val log = LoggerFactory.getLogger(SdkWalletSecretsWipe::class.java)

    /**
     * Entries of the SDK's `WalletStorage` that may survive a wipe. Only the
     * false-locked Keystore defect record (`MASTER_LOCK_DEFECT_KEY` in the
     * SDK, `integration/v42int27`): a device fact, not wallet data. Every
     * per-wallet entry (`mnemonic.`, `mnemonicalias.`, `privkey.`,
     * `privkeyfp.`, `privkeyalias.`, `privkeyowners.`, and anything added
     * later) is a secret by omission.
     */
    val DEVICE_SCOPED_ENTRY_NAMES: Set<String> = setOf("masterkeylockdefect")

    /** The entries of [names] that are (or must be treated as) wallet secrets. */
    fun walletSecretEntries(names: Collection<String>): List<String> =
        names.filterNot { it in DEVICE_SCOPED_ENTRY_NAMES }

    /**
     * Verify-or-erase. Never throws (cancellation aside); the outcome says
     * whether the previous wallet's secrets are gone.
     *
     * @param openStore the LIVE store instance; throws when none can be had.
     * @param rawDeleteFile last resort when no instance can be opened: delete
     *   the DataStore file directly; returns true only when the file is
     *   confirmed absent afterwards.
     */
    suspend fun erase(
        openStore: () -> SdkSecretsStore,
        rawDeleteFile: () -> Boolean
    ): SdkSecretsWipeOutcome {
        val store = try {
            openStore()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("SDK secrets wipe: no SDK WalletStorage instance available — raw-deleting the file", t)
            return rawDelete(rawDeleteFile)
        }

        val leftover: List<String>? = try {
            walletSecretEntries(store.listEntryNames())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("SDK secrets wipe: could not list WalletStorage entries — erasing the whole store", t)
            null
        }
        if (leftover != null && leftover.isEmpty()) {
            log.info(
                "SDK secrets wipe: verified no wallet secrets remain after the SDK wallet removal — " +
                    "store kept, device-scoped records preserved"
            )
            return SdkSecretsWipeOutcome.VERIFIED_CLEAN_KEPT
        }
        if (leftover != null) {
            // Entry NAMES carry wallet ids / pubkey hashes: log the shape, not the names.
            log.warn(
                "SDK secrets wipe: {} wallet-secret entr(y/ies) survived the SDK wallet removal " +
                    "(prefixes: {}) — erasing the whole store, the defect record goes with it",
                leftover.size,
                leftover.map { it.substringBefore('.') }.toSortedSet()
            )
        }

        return try {
            store.deleteAll()
            log.warn("SDK secrets wipe: whole store erased through the live WalletStorage instance")
            SdkSecretsWipeOutcome.DELETED_ALL
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A raw delete under a LIVE instance can be undone by its next write
            // (the cache still holds the old entries), so it does not count as
            // erased — but it still removes the durable copy right now, and the
            // retained wipe marker makes the next launch erase it properly.
            log.error("SDK secrets wipe: WalletStorage.deleteAll failed — raw-deleting as a stopgap", t)
            val gone = runCatching { rawDeleteFile() }.getOrDefault(false)
            log.error(
                "SDK secrets wipe FAILED: previous wallet secrets cannot be confirmed erased " +
                    "(stopgap raw delete {}); the wipe stays pending",
                if (gone) "removed the file" else "also failed"
            )
            SdkSecretsWipeOutcome.FAILED
        }
    }

    private fun rawDelete(rawDeleteFile: () -> Boolean): SdkSecretsWipeOutcome {
        val gone = try {
            rawDeleteFile()
        } catch (t: Throwable) {
            log.error("SDK secrets wipe: raw delete threw", t)
            false
        }
        return if (gone) {
            log.warn("SDK secrets wipe: secrets file raw-deleted (defect record dropped)")
            SdkSecretsWipeOutcome.RAW_DELETED
        } else {
            log.error("SDK secrets wipe FAILED: the secrets file could not be deleted; the wipe stays pending")
            SdkSecretsWipeOutcome.FAILED
        }
    }
}

/**
 * Production wiring for [SdkWalletSecretsWipe]: the store is the running
 * SDK's own [WalletStorage] when the SDK is up, otherwise a fresh
 * [WalletStorage] — which is the SAME DataStore instance (the SDK binds its
 * DataStore with a process-wide `preferencesDataStore` delegate), so it is
 * also correct after `DashSdkService.stop()`, when the instance stays live
 * but the runtime no longer holds it, and a raw file delete would split
 * cache from disk.
 */
@Singleton
class SdkWalletSecretsEraser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdkService: DashSdkService
) {
    suspend fun eraseForWalletWipe(rawDeleteFile: () -> Boolean): SdkSecretsWipeOutcome =
        SdkWalletSecretsWipe.erase(
            openStore = {
                val storage = sdkService.walletStorageOrNull()
                    ?: WalletStorage(context, KeySecurityPolicy.DEVICE_BOUND)
                WalletStorageSecretsStore(storage)
            },
            rawDeleteFile = rawDeleteFile
        )

    private class WalletStorageSecretsStore(private val storage: WalletStorage) : SdkSecretsStore {
        override suspend fun listEntryNames(): List<String> = storage.listEntryNames()
        override suspend fun deleteAll() = storage.deleteAll()
    }
}
