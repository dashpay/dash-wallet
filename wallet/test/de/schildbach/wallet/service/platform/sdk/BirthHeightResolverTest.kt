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

import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Host-JVM tests for the Phase 5a birth-time → birth-height mapping.
 *
 * The fixture `test/checkpoints-fixture-testnet.txt` is a valid dashj
 * textual checkpoint file (same `TXT CHECKPOINTS 1` format the app ships
 * in `assets/checkpoints-testnet.txt`) containing eight REAL testnet
 * checkpoints, decoded during fixture creation to these (height, header
 * time) pairs:
 *
 * ```
 *    576 → 1423412199        2880 → 1462855304
 *   1152 → 1423414494        3456 → 1506541651
 *   1728 → 1423418846      576000 → 1631701815
 *   2304 → 1462851817      864000 → 1688780804
 * ```
 *
 * CheckpointManager parsing itself is dashj code (host-JVM safe, no
 * Android); these tests exercise the resolver's floor/margin/fallback
 * decisions on top of it.
 */
class BirthHeightResolverTest {

    private val params = TestNet3Params.get()

    private fun fixtureStream(): InputStream = checkNotNull(
        javaClass.getResourceAsStream("/checkpoints-fixture-testnet.txt")
    ) { "checkpoints-fixture-testnet.txt missing from test resources" }

    private fun resolver(open: () -> InputStream = ::fixtureStream) =
        BirthHeightResolver(params, open)

    @Test
    fun safeBirthHeight_clampSubtractsTheWeekMargin() {
        assertEquals(4032, BIRTH_HEIGHT_SAFETY_MARGIN_BLOCKS) // 7d of 2.5-min blocks
        assertEquals(0u, safeBirthHeight(0))
        assertEquals(0u, safeBirthHeight(4031))
        assertEquals(0u, safeBirthHeight(4032))
        assertEquals(1u, safeBirthHeight(4033))
        assertEquals(571_968u, safeBirthHeight(576_000))
    }

    @Test
    fun resolvesToTheCheckpointAtOrBeforeTheBirthTime_minusTheMargin() {
        // Birth just after the height-576000 checkpoint's header time.
        assertEquals(571_968u, resolver().resolve(1_631_701_815L + 100))
        // Between the 576000 and 864000 checkpoints — floors to 576000.
        assertEquals(571_968u, resolver().resolve(1_650_000_000L))
        // Exactly AT a checkpoint time counts as at-or-before.
        assertEquals(859_968u, resolver().resolve(1_688_780_804L))
        // After every checkpoint — the last one wins.
        assertEquals(859_968u, resolver().resolve(1_700_000_000L))
    }

    @Test
    fun earlyBirth_clampsToGenesisInsteadOfGoingNegative() {
        // Checkpoint 2304 is below the 4032-block margin → genesis.
        assertEquals(0u, resolver().resolve(1_462_852_000L))
    }

    @Test
    fun birthBeforeTheFirstCheckpoint_fallsBackToGenesis() {
        // After the testnet genesis header time but before checkpoint 576:
        // dashj returns its height-0 genesis fallback.
        assertEquals(0u, resolver().resolve(1_400_000_000L))
    }

    @Test
    fun unresolvableBirthTimes_neverThrow_andFallBackToGenesis() {
        assertEquals(0u, resolver().resolve(null))
        assertEquals(0u, resolver().resolve(0L))
        assertEquals(0u, resolver().resolve(-42L))
        // At/behind the genesis header time dashj throws internally — the
        // resolver must contain it.
        assertEquals(0u, resolver().resolve(1L))
    }

    @Test
    fun checkpointFileProblems_neverThrow_andFallBackToGenesis() {
        val birth = 1_650_000_000L
        // Opener itself fails (asset missing).
        assertEquals(0u, resolver { throw IOException("no such asset") }.resolve(birth))
        // Garbage content.
        assertEquals(0u, resolver { ByteArrayInputStream("not checkpoints".toByteArray()) }.resolve(birth))
        // Truncated/corrupt body under a valid header.
        assertEquals(
            0u,
            resolver {
                ByteArrayInputStream("TXT CHECKPOINTS 1\n0\n2\nAAAA\n".toByteArray())
            }.resolve(birth)
        )
    }
}
