/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.main

import org.dash.wallet.common.util.ResourceString
import java.time.LocalDate

open class HistoryRowView(
    open val title: ResourceString? = null,
    val localDate: LocalDate? = null
) {
    override fun equals(other: Any?): Boolean {
        return other is HistoryRowView && other.title == title && other.localDate == localDate
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (localDate?.hashCode() ?: 0)
        return result
    }
}
