/*
 * Copyright 2026 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.data

import de.schildbach.wallet.data.CreditBalanceInfo.Companion.LOW_BALANCE
import de.schildbach.wallet.data.CreditBalanceInfo.Companion.MAX_OPERATION_COST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An empty balance blocks an operation; a low one only warns before allowing it. */
class CreditBalanceInfoTest {
    @Test
    fun `below one operation is empty`() {
        val info = CreditBalanceInfo(MAX_OPERATION_COST - 1)
        assertTrue(info.isBalanceEmpty())
        assertTrue(info.isBalanceWarning())
    }

    @Test
    fun `exactly one operation is low but not empty`() {
        val info = CreditBalanceInfo(MAX_OPERATION_COST)
        assertFalse(info.isBalanceEmpty())
        assertTrue(info.isBalanceWarning())
    }

    @Test
    fun `exactly two operations still warns`() {
        val info = CreditBalanceInfo(LOW_BALANCE)
        assertFalse(info.isBalanceEmpty())
        assertTrue(info.isBalanceWarning())
    }

    @Test
    fun `above two operations neither warns nor blocks`() {
        val info = CreditBalanceInfo(LOW_BALANCE + 1)
        assertFalse(info.isBalanceEmpty())
        assertFalse(info.isBalanceWarning())
    }

    @Test
    fun `low balance is two operations`() {
        assertEquals(2 * MAX_OPERATION_COST, LOW_BALANCE)
    }
}
