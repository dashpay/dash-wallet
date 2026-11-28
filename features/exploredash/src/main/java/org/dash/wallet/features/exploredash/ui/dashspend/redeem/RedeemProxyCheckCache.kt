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
package org.dash.wallet.features.exploredash.ui.dashspend.redeem

import java.util.concurrent.ConcurrentHashMap

class RedeemProxyCheckCache(private val ttlMillis: Long, private val now: () -> Long) {
    private val directUntil = ConcurrentHashMap<String, Long>()

    // Only a direct answer is kept, and only briefly: a proxy has to be noticed even when no broadcast comes.
    fun isDirect(host: String?, check: () -> Boolean): Boolean {
        val key = host.orEmpty()
        val time = now()

        if (directUntil[key]?.let { time < it } == true) {
            return true
        }
        val isDirect = check()

        if (isDirect) {
            directUntil[key] = time + ttlMillis
        } else {
            directUntil.remove(key)
        }
        return isDirect
    }

    fun clear() {
        directUntil.clear()
    }
}
