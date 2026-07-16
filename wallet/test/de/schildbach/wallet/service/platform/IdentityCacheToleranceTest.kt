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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the legacy identity-cache CBOR tolerance
 * ([fetchIdentityToleratingCacheError] / [isLegacyIdentityCacheCborFailure]).
 *
 * The legacy dashj identity cache (PlatformStateRepository.storeIdentity via
 * org.dashj.platform.dpp.util.Cbor) cannot serialize a v4.1-platform identity
 * (e.g. an iOS username), throwing IllegalArgumentException("No converter for
 * ...") AFTER the identity was fetched but before Identities.get returns —
 * which previously aborted the whole accept/receive contact-request flow. The
 * cache is a pure optimization, so that specific failure must be swallowed and
 * the operation must continue via a cache-bypassing refetch.
 */
class IdentityCacheToleranceTest {

    private val cborCacheError =
        IllegalArgumentException(
            "No converter for org.dashj.platform.dpp.identity.SingleContractDocumentType@1a2b3c"
        )

    @Test
    fun classifier_matchesTheLegacyCborCacheFailure() {
        assertTrue(isLegacyIdentityCacheCborFailure(cborCacheError))
    }

    @Test
    fun classifier_ignoresUnrelatedFailures() {
        assertFalse(isLegacyIdentityCacheCborFailure(IllegalArgumentException("bad id")))
        assertFalse(isLegacyIdentityCacheCborFailure(IllegalStateException("No converter for X")))
        assertFalse(isLegacyIdentityCacheCborFailure(RuntimeException("network down")))
        assertFalse(isLegacyIdentityCacheCborFailure(IllegalArgumentException(null as String?)))
    }

    @Test
    fun success_returnsCachedGet_withoutBypassing() {
        var bypassed = false
        val result = fetchIdentityToleratingCacheError<String>(
            cachedGet = { "identity" },
            cacheBypassingFetch = { bypassed = true; "should not be used" }
        )
        assertEquals("identity", result)
        assertFalse("cache-bypassing fetch must not run when the cached get succeeds", bypassed)
    }

    @Test
    fun cborCacheFailure_isSwallowed_andOperationContinuesViaBypass() {
        var bypassed = false
        val result = fetchIdentityToleratingCacheError<String>(
            cachedGet = { throw cborCacheError },
            cacheBypassingFetch = { bypassed = true; "recovered-identity" }
        )
        // The fetch itself succeeded; only the cache write failed, so we recover
        // the identity from the cache-bypassing path and the accept/receive flow
        // continues.
        assertTrue("expected the cache-bypassing refetch to run", bypassed)
        assertEquals("recovered-identity", result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unrelatedIllegalArgument_propagates_withoutBypassing() {
        fetchIdentityToleratingCacheError<String>(
            cachedGet = { throw IllegalArgumentException("malformed identifier") },
            cacheBypassingFetch = { throw AssertionError("must not bypass on unrelated errors") }
        )
    }

    @Test(expected = RuntimeException::class)
    fun networkFailure_propagates_withoutBypassing() {
        fetchIdentityToleratingCacheError<String>(
            cachedGet = { throw RuntimeException("DAPI unreachable") },
            cacheBypassingFetch = { throw AssertionError("must not bypass on a real fetch error") }
        )
    }
}
