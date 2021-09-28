package org.dash.wallet.features.exploredash.repository.model

open class SearchResult(
    val id: Int? = -1,
    val active: Boolean? = true,
    val name: String? = ""
) {
    override fun equals(other: Any?): Boolean {
        return id == (other as SearchResult).id
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (active?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}