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

package de.schildbach.wallet.service.platform.work

import de.schildbach.wallet.service.work.BaseWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Buy Credits screen reads these flags to pick its dialog (review 1b15e950af15). */
class PerformTopUpWorkerOutputTest {

    @Test
    fun pendingPaymentRefusal_isFlaggedAsStillConfirming() {
        val output = PerformTopUpWorker.notSentOutput("pending payment inputs unprotected")

        assertTrue(output.getBoolean(PerformTopUpWorker.KEY_PAYMENT_STILL_CONFIRMING, false))
        assertEquals("pending payment inputs unprotected", output.getString(BaseWorker.KEY_ERROR_MESSAGE))
    }

    @Test
    fun otherRefusals_areNotFlagged() {
        val output = PerformTopUpWorker.notSentOutput("resume-gate recovery lookup failed")

        assertFalse(output.getBoolean(PerformTopUpWorker.KEY_PAYMENT_STILL_CONFIRMING, true))
    }
}
