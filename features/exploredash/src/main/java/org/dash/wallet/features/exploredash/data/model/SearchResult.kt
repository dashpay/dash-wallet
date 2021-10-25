/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.features.exploredash.data.model

import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

open class SearchResult(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    @ColumnInfo(name = "active", defaultValue = "1")
    var active: Boolean? = true,
    var name: String? = ""
) {
    override fun equals(other: Any?): Boolean {
        val second = other as SearchResult
        return id == second.id &&
                name == second.name &&
                active == second.active
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (active?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}