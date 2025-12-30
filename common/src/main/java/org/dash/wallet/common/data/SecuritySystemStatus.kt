/*
 * Copyright 2025 Dash Core Group.
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

package org.dash.wallet.common.data

enum class SecuritySystemStatus(
    val value: Int,
    val isHealthy: Boolean,
    val hasFallback: Boolean
) {
    DEAD(0, false, false),
    FALLBACKS(2, false, true),
    HEALTHY(1, true, false),
    HEALTHY_WITH_FALLBACKS(3, true, true);
}