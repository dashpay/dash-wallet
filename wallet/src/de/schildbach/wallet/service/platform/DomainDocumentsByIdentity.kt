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

package de.schildbach.wallet.service.platform

import org.dashj.platform.dapiclient.model.DocumentQuery
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.Documents
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.sdk.platform.Platform
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("DomainDocumentsByIdentity")

/**
 * Every DPNS domain document whose `records.identity` is one of [identityIds] —
 * `platform.names.getList(ids)` WITHOUT its silent truncation.
 *
 * `Names.getList` sends one `records.identity in [ids]` query per 100 ids with
 * no limit, so Drive applies its default cap of 100 DOCUMENTS to each. A
 * contact holding several names spends several of those slots, and as the
 * results are ordered by `records.identity` the identities at the high end of
 * a full batch silently get no names at all. Field log (12.0.0-qa28, 230
 * contacts): 26 contacts — every one of them in the 100-id batch, the
 * highest-sorting ids of it, none in the 32-id batch — logged "domain document
 * for X could not be found, though a profile exists" on every sync.
 *
 * This re-asks for exactly the identities a capped page can have cut short,
 * using the same query shape (no cursor): the page is ordered by
 * `records.identity`, so every identity that sorts BELOW the page's last
 * document is complete, and only the last one and those above it are queried
 * again. Deliberately not a `startAt`/`startAfter` cursor: on an `in` query
 * over the non-unique `records.identity` index, Drive before protocol v14
 * filters the in-values by the cursor document's key, so `startAfter` drops
 * that identity's remaining names (and `startAt` re-returns all of them) —
 * the cursor semantics this would depend on changed with protocol v14.
 */
internal fun Platform.domainDocumentsForIdentities(identityIds: List<Identifier>): List<Document> =
    fetchAllByIdentity(
        identityIds = identityIds,
        pageLimit = Documents.DOCUMENT_LIMIT,
        identityOf = { DomainDocument(it).dashUniqueIdentityId },
        documentIdOf = { it.id }
    ) { ids ->
        documents.get(
            Names.DPNS_DOMAIN_DOCUMENT,
            DocumentQuery.builder()
                .whereIn("records.identity", ids)
                .orderBy("records.identity", true)
                .build()
        )
    }

/**
 * The paging core of [domainDocumentsForIdentities], over any document type
 * whose [query] returns at most [pageLimit] results ordered ascending by the
 * identity [identityOf] reads. Pure — host-testable against a fake query.
 *
 * Batches [identityIds] by [pageLimit] (the `in`-clause size limit) and, per
 * batch, re-queries while a page comes back full: a full page proves only the
 * identities that sort below its last document complete, so those leave the
 * batch and the rest (the last identity included, as it may have been cut off)
 * are asked again. Documents are de-duplicated by [documentIdOf], since the
 * last identity's documents come back twice. Each round either finishes the
 * batch or shrinks it; a page filled entirely by ONE identity cannot shrink
 * it that way, so that identity keeps the page it got (with a warning) and
 * leaves the batch, and the identities strictly above it are queried on.
 */
internal fun <D> fetchAllByIdentity(
    identityIds: List<Identifier>,
    pageLimit: Int,
    identityOf: (D) -> Identifier?,
    documentIdOf: (D) -> Any,
    query: (List<Identifier>) -> List<D>
): List<D> {
    require(pageLimit > 0) { "pageLimit must be positive, got $pageLimit" }
    val documents = LinkedHashMap<Any, D>()
    for (batch in identityIds.distinct().chunked(pageLimit)) {
        var remaining = batch
        while (remaining.isNotEmpty()) {
            val page = query(remaining)
            page.forEach { documents.putIfAbsent(documentIdOf(it), it) }
            if (page.size < pageLimit) break
            val lastIdentity = identityOf(page.last())
            if (lastIdentity == null) {
                log.warn("a full page of {} documents ended without an identity; not paging further", page.size)
                break
            }
            val next = remaining.filter { compareIdentifiers(it, lastIdentity) >= 0 }
            if (next.size >= remaining.size) {
                // The whole page is [lastIdentity]'s. Its further documents
                // are not fetched (that would need a cursor — see
                // [domainDocumentsForIdentities]), but the identities above it
                // are still owed their names: drop only this one and go on.
                log.warn(
                    "identity {} alone fills a {}-document page; its further documents are not fetched",
                    lastIdentity, pageLimit
                )
                val above = remaining.filter { compareIdentifiers(it, lastIdentity) > 0 }
                // Always shrinks when [lastIdentity] is one of [remaining];
                // a page naming an identity not asked for must not loop.
                if (above.size >= remaining.size) break
                remaining = above
                continue
            }
            log.info(
                "document page capped at {} for {} identities; re-querying the {} not yet complete",
                page.size, remaining.size, next.size
            )
            remaining = next
        }
    }
    return documents.values.toList()
}

/**
 * Orders identifiers the way Drive orders an index over them: unsigned,
 * byte by byte over the 32-byte value.
 */
internal fun compareIdentifiers(a: Identifier, b: Identifier): Int {
    val x = a.toBuffer()
    val y = b.toBuffer()
    for (i in 0 until minOf(x.size, y.size)) {
        val cmp = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (cmp != 0) return cmp
    }
    return x.size - y.size
}
