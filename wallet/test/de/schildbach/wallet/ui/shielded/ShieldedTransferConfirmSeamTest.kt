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

package de.schildbach.wallet.ui.shielded

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host-JVM tests for [captureAuthenticateAndSubmit] — the capture-before-authenticate
 * contract behind [ShieldedBalanceActivity.authenticateAndConfirmTransfer]. A Max-pinned
 * amount tracks the LIVE balance, so re-reading it after the (possibly long) PIN/biometric
 * wait could silently submit an amount the user never authorized; these pin that the value
 * [capture] returns BEFORE [authenticate] suspends is exactly what reaches [submit].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldedTransferConfirmSeamTest {

    @Test
    fun capturedAmountSurvivesABalanceChangeDuringAuthentication() = runTest(UnconfinedTestDispatcher()) {
        var liveBalance = Dash.parse("3")
        val authGate = CompletableDeferred<Boolean>()
        var submitted: Dash? = null

        val job = launch {
            captureAuthenticateAndSubmit(
                capture = { liveBalance },
                authenticate = { authGate.await() },
                submit = { submitted = it }
            )
        }

        // the balance moves WHILE authentication is still pending
        liveBalance = Dash.parse("4")
        assertNull(submitted)

        authGate.complete(true)
        job.join()

        // submit saw the amount captured before the wait, not the live balance after it
        assertEquals(Dash.parse("3"), submitted)
    }

    @Test
    fun cancelledOrFailedAuthentication_neverSubmits() = runTest {
        var submitted: Dash? = null

        captureAuthenticateAndSubmit(
            capture = { Dash.parse("3") },
            authenticate = { false },
            submit = { submitted = it }
        )

        assertNull(submitted)
    }
}
