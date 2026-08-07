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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.mockk
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase 3d vote-state read facade: the SDK-JSON →
 * dashj [org.dashj.platform.dpp.voting.Contenders] mapping and the
 * flag/fallback orchestration. No native calls — the native query surface is
 * faked via [SdkVotingSource].
 *
 * Fixture JSON mirrors the shape produced by the SDK's FFI layer
 * (`rs-sdk-ffi/src/contested_resource/queries/vote_state.rs`):
 * `abstain_vote_tally`/`lock_vote_tally` numbers, `winner_info` as
 * `"NoWinner"`/`"Locked"`/`{"type":"WonByIdentity","identity_id":…}` with a
 * `block_info` object, and `contenders` entries of
 * `{"identity_id","vote_count","document"(hex)|null}`. An empty contest is
 * a null payload (FFI NoData), not empty JSON.
 */
class SdkVotingQueriesTest {

    private val dpnsContractId = "GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec"
    private val identityId = "5DbLwAxGBzUzo81VewMUwn4b5P4bpv9FNFybi25XB5Bk"
    private val otherIdentityId = Identifier.from(ByteArray(32) { 2 }).toString()

    // ── SdkVotingMapping ─────────────────────────────────────────────────

    @Test
    fun voteStateJson_activeContest_mapsContendersAndTallies() {
        val json = """
            {
              "abstain_vote_tally": 3,
              "lock_vote_tally": 5,
              "contenders": [
                {"identity_id":"$identityId","vote_count":7,"document":"00ff10"},
                {"identity_id":"$otherIdentityId","vote_count":2,"document":null}
              ]
            }
        """.trimIndent()

        val contenders = SdkVotingMapping.contendersFromVoteStateJson(json)

        assertNotNull(contenders)
        assertEquals(3, contenders!!.abstainVoteTally)
        assertEquals(5, contenders.lockVoteTally)
        assertTrue(contenders.winner.isEmpty)
        assertEquals(2, contenders.map.size)

        val first = contenders.map[Identifier.from(identityId)]!!
        assertEquals(7, first.votes)
        assertArrayEquals(byteArrayOf(0x00, 0xff.toByte(), 0x10), first.serializedDocument)

        val second = contenders.map[Identifier.from(otherIdentityId)]!!
        assertEquals(2, second.votes)
        assertNull(second.serializedDocument)
    }

    @Test
    fun voteStateJson_wonByIdentity_mapsWinner() {
        val json = """
            {
              "abstain_vote_tally": 0,
              "lock_vote_tally": 1,
              "winner_info": {"type":"WonByIdentity","identity_id":"$identityId"},
              "block_info": {"height":12345,"core_height":678,"timestamp":1700000000000},
              "contenders": [
                {"identity_id":"$identityId","vote_count":9,"document":"aa"}
              ]
            }
        """.trimIndent()

        val contenders = SdkVotingMapping.contendersFromVoteStateJson(json)!!

        assertTrue(contenders.winner.isPresent)
        val (winnerInfo, blockInfo) = contenders.winner.get()
        assertTrue(winnerInfo.isWinner(Identifier.from(identityId)))
        assertFalse(winnerInfo.isWinner(Identifier.from(otherIdentityId)))
        assertFalse(winnerInfo.isLocked)
        assertFalse(winnerInfo.noWinner)
        assertEquals(12345, blockInfo.height)
        assertEquals(678L, blockInfo.coreHeight)
        assertEquals(1700000000000L, blockInfo.time)
    }

    @Test
    fun voteStateJson_locked_mapsWinnerInfo() {
        val json = """
            {
              "abstain_vote_tally": 1,
              "lock_vote_tally": 20,
              "winner_info": "Locked",
              "block_info": {"height":1,"core_height":1,"timestamp":1},
              "contenders": [
                {"identity_id":"$identityId","vote_count":4,"document":"bb"}
              ]
            }
        """.trimIndent()

        val contenders = SdkVotingMapping.contendersFromVoteStateJson(json)!!

        assertTrue(contenders.winner.isPresent)
        val winnerInfo = contenders.winner.get().first
        assertTrue(winnerInfo.isLocked)
        assertFalse(winnerInfo.noWinner)
        assertFalse(winnerInfo.isWinner(Identifier.from(identityId)))
        assertEquals(20, contenders.lockVoteTally)
    }

    @Test
    fun voteStateJson_noWinner_mapsWinnerInfo() {
        val json = """
            {
              "winner_info": "NoWinner",
              "block_info": {"height":2,"core_height":2,"timestamp":2},
              "contenders": [
                {"identity_id":"$identityId","vote_count":0,"document":null}
              ]
            }
        """.trimIndent()

        val contenders = SdkVotingMapping.contendersFromVoteStateJson(json)!!

        assertTrue(contenders.winner.isPresent)
        val winnerInfo = contenders.winner.get().first
        assertTrue(winnerInfo.noWinner)
        assertFalse(winnerInfo.isLocked)
        // Missing tallies (result type without them) default to 0.
        assertEquals(0, contenders.abstainVoteTally)
        assertEquals(0, contenders.lockVoteTally)
    }

    @Test
    fun voteStateJson_skipsMalformedContenderEntries() {
        val json = """
            {
              "abstain_vote_tally": 0,
              "lock_vote_tally": 0,
              "contenders": [
                {"identity_id":"$identityId","vote_count":1,"document":"cc"},
                {"vote_count":9,"document":"dd"},
                {"identity_id":"not-a-valid-identifier###","vote_count":9},
                "not-an-object"
              ]
            }
        """.trimIndent()

        val contenders = SdkVotingMapping.contendersFromVoteStateJson(json)!!

        assertEquals(1, contenders.map.size)
        assertNotNull(contenders.map[Identifier.from(identityId)])
    }

    @Test
    fun voteStateJson_malformed_returnsNull() {
        // not an object
        assertNull(SdkVotingMapping.contendersFromVoteStateJson("""["x"]"""))
        // not JSON
        assertNull(SdkVotingMapping.contendersFromVoteStateJson("garbage{"))
        // contenders present but not an array
        assertNull(SdkVotingMapping.contendersFromVoteStateJson("""{"contenders":{"a":1}}"""))
        // unrecognizable winner_info must not be silently dropped — a decided
        // contest must never be misreported as undecided
        assertNull(
            SdkVotingMapping.contendersFromVoteStateJson(
                """{"winner_info":"SomethingNew","contenders":[]}"""
            )
        )
        assertNull(
            SdkVotingMapping.contendersFromVoteStateJson(
                """{"winner_info":{"type":"WonByIdentity"},"contenders":[]}"""
            )
        )
    }

    @Test
    fun indexValuesJson_escapesAndWrapsLabel() {
        assertEquals("""["dash","b0b"]""", SdkVotingQueries.indexValuesJson("b0b"))
        assertEquals("""["dash","a\"b\\c"]""", SdkVotingQueries.indexValuesJson("""a"b\c"""))
    }

    // ── Facade orchestration ─────────────────────────────────────────────

    private class FakeSource(
        var result: () -> String? = { null }
    ) : SdkVotingSource {
        var calls = 0
        var contractId: String? = null
        var documentTypeName: String? = null
        var indexName: String? = null
        var indexValuesJson: String? = null
        var resultType: Int? = null
        var allowInclude: Boolean? = null
        var count: Int? = null

        override suspend fun contestedResourceVoteState(
            contractId: String,
            documentTypeName: String,
            indexName: String,
            indexValuesJson: String,
            resultType: Int,
            allowIncludeLockedAndAbstaining: Boolean,
            count: Int
        ): String? {
            calls++
            this.contractId = contractId
            this.documentTypeName = documentTypeName
            this.indexName = indexName
            this.indexValuesJson = indexValuesJson
            this.resultType = resultType
            this.allowInclude = allowIncludeLockedAndAbstaining
            this.count = count
            return result()
        }
    }

    private fun config(enabled: Boolean): DashPayConfig = mockk {
        coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } returns enabled
    }

    private fun queries(
        source: FakeSource,
        enabled: Boolean = true,
        contractId: Identifier? = Identifier.from(dpnsContractId)
    ) = SdkVotingQueries(source, config(enabled), { contractId })

    @Test
    fun flagOff_returnsNull_andNeverTouchesSdk() {
        val source = FakeSource()
        assertNull(queries(source, enabled = false).getVoteContendersOrNull("alice"))
        assertEquals(0, source.calls)
    }

    @Test
    fun missingContractId_returnsNull_forDashjFallback() {
        val source = FakeSource(result = { """{"contenders":[]}""" })
        assertNull(queries(source, contractId = null).getVoteContendersOrNull("alice"))
        assertEquals(0, source.calls)
    }

    @Test
    fun activeContest_mapsResult_andQueriesWithDashjParity() {
        val source = FakeSource(
            result = {
                """{"abstain_vote_tally":0,"lock_vote_tally":2,
                    "contenders":[{"identity_id":"$identityId","vote_count":4,"document":"ee"}]}"""
            }
        )

        val contenders = queries(source).getVoteContendersOrNull("Alice")

        assertNotNull(contenders)
        assertEquals(1, contenders!!.map.size)
        assertEquals(4, contenders.map[Identifier.from(identityId)]!!.votes)
        assertEquals(2, contenders.lockVoteTally)
        // Query parity with dashj Names.getVoteContenders(normalized):
        assertEquals(dpnsContractId, source.contractId)
        assertEquals("domain", source.documentTypeName)
        assertEquals("parentNameAndLabel", source.indexName)
        // Names.normalizeString maps o→0 and i/l→1 after lowercasing.
        assertEquals("""["dash","a11ce"]""", source.indexValuesJson)
        assertEquals(2, source.resultType) // documents + vote tally
        assertEquals(true, source.allowInclude)
        assertEquals(0, source.count)
    }

    @Test
    fun noContenders_nullPayload_returnsEmptyContenders_likeDashj() {
        val source = FakeSource(result = { null })

        val contenders = queries(source).getVoteContendersOrNull("alice")

        assertNotNull(contenders)
        assertTrue(contenders!!.isEmpty())
        assertTrue(contenders.map.isEmpty())
        assertTrue(contenders.winner.isEmpty)
        assertEquals(0, contenders.lockVoteTally)
    }

    @Test
    fun sdkFailure_returnsNull_forDashjFallback() {
        val failing = FakeSource(result = { throw DashSdkError.NetworkError("no quorum") })
        assertNull(queries(failing).getVoteContendersOrNull("alice"))

        val malformed = FakeSource(result = { "garbage{" })
        assertNull(queries(malformed).getVoteContendersOrNull("alice"))
    }
}
