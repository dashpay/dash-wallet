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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.util.Converters
import org.dashj.platform.dpp.voting.BlockInfo
import org.dashj.platform.dpp.voting.ContenderWithSerializedDocument
import org.dashj.platform.dpp.voting.Contenders
import org.dashj.platform.dpp.voting.ContestedDocumentVotePollWinnerInfo
import org.dashj.platform.dpp.voting.Epoch
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over the Kotlin SDK's native contested-resource vote-state query
 * (`sdk.voting.contestedResourceVoteState`), so the mapping/orchestration in
 * [SdkVotingQueries] is host-JVM unit-testable with fake JSON — the native
 * call itself needs `libdash_sdk`.
 */
interface SdkVotingSource {
    /**
     * Vote state (contenders + tallies + winner) for one contested resource,
     * as the FFI's JSON object (see [SdkVotingMapping.contendersFromVoteStateJson]),
     * or null when the SDK definitively reported no contenders
     * (`rs-sdk-ffi/src/contested_resource/queries/vote_state.rs` returns
     * NoData when the fetched contender map is empty).
     */
    suspend fun contestedResourceVoteState(
        contractId: String,
        documentTypeName: String,
        indexName: String,
        indexValuesJson: String,
        resultType: Int,
        allowIncludeLockedAndAbstaining: Boolean,
        count: Int
    ): String?
}

/** Production [SdkVotingSource]: boots the SDK on demand and queries `sdk.voting`. */
internal class DashSdkVotingSource(private val service: DashSdkService) : SdkVotingSource {

    private suspend fun sdk(): org.dashfoundation.dashsdk.Sdk {
        service.ensureStarted()
        return checkNotNull(service.sdkOrNull()) { "SDK runtime missing after ensureStarted()" }
    }

    override suspend fun contestedResourceVoteState(
        contractId: String,
        documentTypeName: String,
        indexName: String,
        indexValuesJson: String,
        resultType: Int,
        allowIncludeLockedAndAbstaining: Boolean,
        count: Int
    ): String? = sdk().voting.contestedResourceVoteState(
        contractId = contractId,
        documentTypeName = documentTypeName,
        indexName = indexName,
        indexValuesJson = indexValuesJson,
        resultType = resultType,
        allowIncludeLockedAndAbstaining = allowIncludeLockedAndAbstaining,
        count = count
    )
}

/**
 * Pure JSON → dashj-model mapping for the Kotlin SDK's contested-resource
 * vote-state result — the Phase 3d translation layer
 * (`docs/kotlin-sdk-migration-plan.md`).
 *
 * The FFI (`rs-sdk-ffi/src/contested_resource/queries/vote_state.rs`) emits:
 * ```
 * {
 *   "abstain_vote_tally": <u32>, "lock_vote_tally": <u32>,   // resultType has tally
 *   "winner_info": "NoWinner" | "Locked"
 *                | {"type":"WonByIdentity","identity_id":"<base58>"},  // only when decided
 *   "block_info": {"height":H,"core_height":C,"timestamp":T},          // with winner_info
 *   "contenders": [                                          // resultType has documents
 *     {"identity_id":"<base58>","vote_count":N,"document":"<hex>"|null}, …
 *   ]
 * }
 * ```
 * `document` is the contender's DPP bincode-serialized domain document —
 * byte-identical to what dashj's own `getVoteContenders` carries in
 * `ContenderWithSerializedDocument.serializedDocument`, because both stacks
 * surface rs-sdk's `ContenderWithSerializedDocument::serialized_document()`.
 * The routed callers keep deserializing it with dashj
 * (`platform.names.deserialize`), so no document synthesis is needed here.
 *
 * Everything here is pure JVM (dashj-platform model classes + Gson): no
 * native calls, no Android, so it is covered by host unit tests.
 */
object SdkVotingMapping {

    /**
     * Map a vote-state payload to dashj [Contenders], or null if the JSON is
     * malformed (caller falls back to dashj). Contender entries missing
     * required fields are skipped defensively.
     *
     * The winner pair's [BlockInfo] is filled from `block_info` when present;
     * the epoch (not reported by the FFI) is a zero placeholder — no wallet
     * caller reads it (they read only the winner info: `isLocked`,
     * `isWinner`, `noWinner`).
     */
    fun contendersFromVoteStateJson(json: String): Contenders? {
        val root = parseOrNull(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null

        val map = LinkedHashMap<Identifier, ContenderWithSerializedDocument>()
        val contendersElement = root.get("contenders")
        if (contendersElement != null && !contendersElement.isJsonArray) return null
        contendersElement?.asJsonArray?.forEach { element ->
            val obj = (element as? JsonObject) ?: return@forEach
            val identityId = obj.stringOrNull("identity_id") ?: return@forEach
            val identifier = try {
                Identifier.from(identityId)
            } catch (e: Exception) {
                return@forEach
            }
            val votes = obj.intOrNull("vote_count") ?: 0
            val serializedDocument = obj.stringOrNull("document")?.let { hex ->
                try {
                    Converters.fromHex(hex)
                } catch (e: Exception) {
                    null
                }
            }
            map[identifier] = ContenderWithSerializedDocument(identifier, serializedDocument, votes)
        }

        val winner = winnerFromJson(root) ?: return null

        return Contenders(
            winner,
            map,
            root.intOrNull("abstain_vote_tally") ?: 0,
            root.intOrNull("lock_vote_tally") ?: 0
        )
    }

    /** dashj's "no contest / query failed" value; also the SDK's empty result. */
    fun emptyContenders(): Contenders = Contenders(Optional.empty(), mapOf(), 0, 0)

    /**
     * `winner_info` → dashj winner pair. Absent = contest still in voting
     * (Optional.empty). Returns null (outer null = malformed → dashj
     * fallback) when present but unrecognizable, so a decided contest is
     * never misreported as undecided.
     */
    private fun winnerFromJson(
        root: JsonObject
    ): Optional<kotlin.Pair<ContestedDocumentVotePollWinnerInfo, BlockInfo>>? {
        val info = root.get("winner_info") ?: return Optional.empty()

        val winnerInfo = when {
            info.isJsonPrimitive && info.asJsonPrimitive.isString -> when (info.asString) {
                "NoWinner" -> ContestedDocumentVotePollWinnerInfo(
                    org.dashj.platform.sdk.ContestedDocumentVotePollWinnerInfo.Tag.NoWinner,
                    null
                )
                "Locked" -> ContestedDocumentVotePollWinnerInfo(
                    org.dashj.platform.sdk.ContestedDocumentVotePollWinnerInfo.Tag.Locked,
                    null
                )
                else -> return null
            }
            info.isJsonObject -> {
                val obj = info.asJsonObject
                if (obj.stringOrNull("type") != "WonByIdentity") return null
                val identityId = obj.stringOrNull("identity_id") ?: return null
                val identifier = try {
                    Identifier.from(identityId)
                } catch (e: Exception) {
                    return null
                }
                ContestedDocumentVotePollWinnerInfo(
                    org.dashj.platform.sdk.ContestedDocumentVotePollWinnerInfo.Tag.WonByIdentity,
                    identifier
                )
            }
            else -> return null
        }

        val blockInfo = root.get("block_info")?.takeIf { it.isJsonObject }?.asJsonObject
        return Optional.of(
            kotlin.Pair(
                winnerInfo,
                BlockInfo(
                    Epoch(0, ByteArray(0)),
                    blockInfo?.intOrNull("height") ?: 0,
                    blockInfo?.longOrNull("timestamp") ?: 0L,
                    blockInfo?.longOrNull("core_height") ?: 0L
                )
            )
        )
    }

    private fun parseOrNull(json: String): JsonElement? =
        try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            null
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}

/**
 * Phase 3d facade: routes the wallet's contested-username vote-state reads
 * to the Dash Platform Kotlin SDK behind the SAME runtime flag as the Phase
 * 3c DPNS reads, [DashPayConfig.USE_KOTLIN_SDK_DPNS_READS] (default OFF).
 *
 * ## Contract (identical to [SdkUsernameQueries])
 *
 * - Returns **null when the SDK path must not be used** — flag off, SDK
 *   bootstrap failure, native query failure, or malformed payload — and the
 *   call site runs its existing dashj-platform code unchanged.
 * - Flag off (the default) leaves the dashj path byte-for-byte intact.
 * - The flag is re-read per lookup; toggles take effect on the next call.
 *
 * Routed call site: [de.schildbach.wallet.ui.dashpay.PlatformRepo.getVoteContenders]
 * — which serves `RequestUserNameViewModel.checkUsername`,
 * `PlatformSyncService.updateUsernameRequest(s)WithVotes` /
 * `checkUsernameVotingStatus`, `CreateIdentityService`, and
 * `RestoreIdentityWorker`.
 *
 * Query parity with dashj `Names.getVoteContenders(normalizedLabel)`: DPNS
 * contract, document type `domain`, index `parentNameAndLabel`, index values
 * `["dash", <normalizedLabel>]`, documents+tally, locked/abstaining included.
 */
@Singleton
class SdkVotingQueries internal constructor(
    private val source: SdkVotingSource,
    private val dashPayConfig: DashPayConfig,
    private val dpnsContractId: () -> Identifier?
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        platformService: PlatformService
    ) : this(
        source = DashSdkVotingSource(sdkService),
        dashPayConfig = dashPayConfig,
        dpnsContractId = { platformService.platform.apps["dpns"]?.contractId }
    )

    /**
     * SDK-path replacement for `platform.names.getVoteContenders(normalized)`
     * — the exact value shape of `PlatformRepo.getVoteContenders`.
     *
     * @return mapped [Contenders]; [SdkVotingMapping.emptyContenders] when
     *   the SDK definitively reported no contenders (dashj parity: an
     *   uncontested name yields an empty contender map); or **null** when
     *   the caller must fall back to dashj.
     *
     * Blocking by design: the caller is a non-suspend repository method
     * already invoked on background threads, where the dashj path performs
     * blocking network I/O of its own.
     */
    fun getVoteContendersOrNull(username: String): Contenders? = try {
        runBlocking { voteContenders(username) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("SDK vote-state query failed; falling back to dashj for getVoteContenders", t)
        null
    }

    private suspend fun voteContenders(username: String): Contenders? {
        if (!isEnabled()) return null
        val contractId = dpnsContractId()
        if (contractId == null) {
            log.warn("DPNS contract id unavailable; falling back to dashj")
            return null
        }
        val normalized = Names.normalizeString(username)
        val json = source.contestedResourceVoteState(
            contractId = contractId.toString(),
            documentTypeName = DOCUMENT_TYPE,
            indexName = INDEX_NAME,
            indexValuesJson = indexValuesJson(normalized),
            resultType = RESULT_TYPE_DOCUMENTS_AND_VOTE_TALLY,
            allowIncludeLockedAndAbstaining = true,
            count = 0
        ) ?: return SdkVotingMapping.emptyContenders()
        val contenders = SdkVotingMapping.contendersFromVoteStateJson(json)
        if (contenders == null) {
            log.warn("SDK vote-state returned malformed payload; falling back to dashj")
        }
        return contenders
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_DPNS_READS; keeping dashj path", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkVotingQueries::class.java)

        private const val DOCUMENT_TYPE = "domain"
        private const val INDEX_NAME = "parentNameAndLabel"

        /** rs-sdk-ffi result-type discriminant: documents + vote tally. */
        private const val RESULT_TYPE_DOCUMENTS_AND_VOTE_TALLY = 2

        /**
         * JSON index-values array `["dash", <label>]` for the DPNS
         * `parentNameAndLabel` index, with minimal escaping (labels are
         * already restricted to `[a-zA-Z0-9-]` upstream; escaping keeps this
         * safe even for un-validated input).
         */
        internal fun indexValuesJson(normalizedLabel: String): String {
            val escaped = normalizedLabel.replace("\\", "\\\\").replace("\"", "\\\"")
            return "[\"dash\",\"$escaped\"]"
        }
    }
}
