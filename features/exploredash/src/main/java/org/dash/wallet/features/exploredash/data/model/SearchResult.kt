package org.dash.wallet.features.exploredash.data.model

import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

open class SearchResult(
    @PrimaryKey
    var id: Int = -1,
    @ColumnInfo(name = "active", defaultValue = "1")
    var active: Boolean? = true,
    var name: String? = ""
) {
    override fun equals(other: Any?): Boolean {
        return id == (other as SearchResult).id
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (active?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}