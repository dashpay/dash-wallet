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
package de.schildbach.wallet.ui.dashpay.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [classifyVoteFailure] / [voteFailureText] — deciding whether a
 * vote-broadcast error is actually a failure at all.
 *
 * Pins the emulator-5554 finding (testnet, build 12000013): voting a contested username
 * with a masternode that had already voted that poll surfaced as a hard failure —
 *
 *     E/BroadcastUsernameVotesWorker: all votes failed: errors: 1 vs total submitted 1
 *     java.lang.Exception: Attempted to unwrap a Failure: Protocol error:
 *       Masternode vote is already present for masternode DghTta8E4ySZsozAoF4WjnY
 *
 * "Vote is already present" means the masternode ALREADY voted this poll: the desired
 * end state is already true, so it reconciles as success. The neighbouring "can only
 * vote 5 times" error, which the worker's comments have long anticipated alongside it,
 * is genuinely terminal and must NOT collapse into the same verdict.
 *
 * The full engine messages below are copied verbatim from the field and from
 * `BroadcastUsernameVotesWorker`'s own recorded samples. They are the point of this
 * file: a future SDK reword breaks these assertions loudly instead of silently turning
 * a duplicate vote back into a fatal error.
 */
class VoteFailureClassificationTest {

    companion object {
        /** Verbatim, as logged on emulator-5554. */
        private const val FIELD_ALREADY_PRESENT =
            "Attempted to unwrap a Failure: Protocol error: Masternode vote is already " +
                "present for masternode DghTta8E4ySZsozAoF4WjnY"

        /** Verbatim, from the recorded DAPI transport error. */
        private const val DAPI_ALREADY_PRESENT =
            "Dapi client error: Transport(Status { code: InvalidArgument, message: " +
                "\"Masternode vote is already present for masternode " +
                "EbitFAjpGsuf7qKPpsQMZw2ZKZ8rs2S1PdqKvYA8J2Ux voting for " +
                "ContestedDocumentResourceVotePoll(ContestedDocumentResourceVotePoll { " +
                "contract_id: GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec, " +
                "document_type_name: domain, index_name: parentNameAndLabel, " +
                "index_values: [string dash, string test-1101] })\""

        /** Verbatim, from the recorded DAPI transport error. */
        private const val DAPI_VOTE_LIMIT =
            "Dapi client error: Transport(Status { code: InvalidArgument, message: " +
                "\"Masternode with id: CmbJumQ1ALJXHYFpUdCCnvbfgvXKSajErNXGhv3H4GN1 " +
                "already voted 5 times and is trying to vote again, they can only vote " +
                "5 times\""
    }

    @Test
    fun `the already-present vote is not a failure`() {
        assertEquals(VoteFailureVerdict.ALREADY_CAST, classifyVoteFailure(FIELD_ALREADY_PRESENT))
        assertEquals(VoteFailureVerdict.ALREADY_CAST, classifyVoteFailure(DAPI_ALREADY_PRESENT))
        assertFalse(VoteFailureVerdict.ALREADY_CAST.isTerminal)
    }

    @Test
    fun `the spent vote budget stays terminal`() {
        // This message ALSO says "already voted" - it must not be swept in with the
        // already-present case, which is why the limit marker is matched first.
        assertEquals(VoteFailureVerdict.VOTE_LIMIT_REACHED, classifyVoteFailure(DAPI_VOTE_LIMIT))
        assertTrue(VoteFailureVerdict.VOTE_LIMIT_REACHED.isTerminal)
    }

    @Test
    fun `the two verdicts are distinct`() {
        assertTrue(classifyVoteFailure(DAPI_ALREADY_PRESENT) != classifyVoteFailure(DAPI_VOTE_LIMIT))
    }

    @Test
    fun `the markers really occur in the messages they claim to match`() {
        // Guards the markers themselves: if an SDK reword drops the phrase, this fails
        // here rather than quietly downgrading every duplicate vote to FAILED.
        assertTrue(FIELD_ALREADY_PRESENT.lowercase().contains(ALREADY_CAST_MARKER))
        assertTrue(DAPI_ALREADY_PRESENT.lowercase().contains(ALREADY_CAST_MARKER))
        assertTrue(DAPI_VOTE_LIMIT.lowercase().contains(VOTE_LIMIT_MARKER))
        assertFalse(DAPI_ALREADY_PRESENT.lowercase().contains(VOTE_LIMIT_MARKER))
    }

    @Test
    fun `anything unrecognised is a real failure`() {
        assertEquals(VoteFailureVerdict.FAILED, classifyVoteFailure("Connection reset by peer"))
        assertEquals(VoteFailureVerdict.FAILED, classifyVoteFailure(""))
        assertEquals(VoteFailureVerdict.FAILED, classifyVoteFailure(null))
        assertTrue(VoteFailureVerdict.FAILED.isTerminal)
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(
            VoteFailureVerdict.ALREADY_CAST,
            classifyVoteFailure("MASTERNODE VOTE IS ALREADY PRESENT FOR MASTERNODE X")
        )
    }

    @Test
    fun `the marker is found through a wrapped cause chain`() {
        // The field exception arrives wrapped; a future SDK may nest it deeper still.
        val wrapped = Exception(
            "broadcast username vote failed",
            RuntimeException("Attempted to unwrap a Failure", IllegalStateException(DAPI_ALREADY_PRESENT))
        )
        assertEquals(VoteFailureVerdict.ALREADY_CAST, classifyVoteFailure(voteFailureText(wrapped)))
    }

    @Test
    fun `a self-referencing cause chain terminates`() {
        val a = Exception("outer")
        val b = Exception("inner", a)
        a.initCause(b)
        assertEquals("outer | inner", voteFailureText(a))
    }

    @Test
    fun `a null or message-less error yields no text and so stays a failure`() {
        assertEquals("", voteFailureText(null))
        assertEquals(VoteFailureVerdict.FAILED, classifyVoteFailure(voteFailureText(Exception())))
    }
}
