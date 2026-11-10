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

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One lock per identity over the app's DPNS registrations: a caller holds it across
 * "re-check ownership → `registerDpnsName` → record the result", so no other in-app registration
 * for the same identity can land between its re-check and its submit.
 *
 * Shared by [SdkTransparentUsernameCreation] (create path, name-only retry and
 * [SdkTransparentUsernameCreation.registerDpnsNameForExistingIdentity], which the restore worker
 * and the invite flow use) and [SdkShieldedUsernameCreation].
 *
 * Lock order: this lock is taken without holding the wipe fence's permit or the creations'
 * single-flight monitor, and neither is taken while it is held, so it cannot deadlock with them.
 * Waiting for it is cancellable (a wipe cancelling a tracked job stops the wait), and it is
 * released on any exit, cancellation included.
 */
@Singleton
class DpnsRegistrationLock @Inject constructor() {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = HashMap<String, Entry>()

    /**
     * Run [block] holding [identityIdBase58]'s lock. `waited` is true when another registration
     * held it first: whatever that one registered is on chain by now, so ownership must be re-read.
     */
    suspend fun <T> withIdentityLock(identityIdBase58: String, block: suspend (waited: Boolean) -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(identityIdBase58) { Entry() }.also { it.users++ }
        }
        try {
            val waited = !entry.mutex.tryLock()
            // cancellable: a cancelled wait never acquires, so there is nothing to unlock
            if (waited) entry.mutex.lock()
            try {
                return block(waited)
            } finally {
                entry.mutex.unlock()
            }
        } finally {
            synchronized(entries) {
                if (--entry.users == 0) entries.remove(identityIdBase58)
            }
        }
    }
}
