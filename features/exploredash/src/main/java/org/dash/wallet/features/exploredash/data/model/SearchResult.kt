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
import com.google.firebase.database.PropertyName

open class SearchResult(
    @PrimaryKey
    var id: Int = -1,
    @ColumnInfo(name = "active", defaultValue = "1")
    var active: Boolean? = true,
    var name: String? = "",
    var address1: String? = "",
    var address2: String? = "",
    var address3: String? = "",
    var address4: String? = "",
    var latitude: Double? = 0.0,
    var longitude: Double? = 0.0,
    var website: String? = "",
    var phone: String? = "",
    @get:PropertyName("logo_location") @set:PropertyName("logo_location")
    var logoLocation: String? = "",
    open var territory: String? = "",
    open var type: String? = ""
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