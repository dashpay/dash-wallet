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
 * Host-JVM tests for the failed-contact-update retry seam
 * ([ContactUpdateRetryPolicy]).
 *
 * Regression context (S21 build 11.10.45, 2026-08-02): a failed
 * `updateContactRequests(true)` run was never retried, so discovery of an
 * iPhone contact-request acceptance waited ~16 minutes for an unrelated
 * home-screen-resume trigger. A failed run must warrant its own bounded retry;
 * a persistent failure must exhaust the budget instead of retrying forever;
 * and any success must reset both the budget and a pending retry.
 */
class ContactUpdateRetryPolicyTest {

    @Test
    fun singleFailure_warrantsARetry() {
        val policy = ContactUpdateRetryPolicy()
        assertTrue("the first failure must schedule a retry", policy.onFailureShouldRetry())
        assertEquals(1, policy.failureCount)
        assertTrue(policy.retryStillWarranted)
    }

    @Test
    fun persistentFailure_stopsRetryingAtTheBudget() {
        val policy = ContactUpdateRetryPolicy(maxConsecutiveRetries = 3)
        assertTrue(policy.onFailureShouldRetry()) // failure 1 -> retry 1
        assertTrue(policy.onFailureShouldRetry()) // failure 2 -> retry 2
        assertTrue(policy.onFailureShouldRetry()) // failure 3 -> retry 3
        assertFalse("budget exhausted — must go quiet", policy.onFailureShouldRetry())
        assertFalse("still quiet on further failures", policy.onFailureShouldRetry())
    }

    @Test
    fun success_resetsTheBudget() {
        val policy = ContactUpdateRetryPolicy(maxConsecutiveRetries = 1)
        assertTrue(policy.onFailureShouldRetry())
        assertFalse(policy.onFailureShouldRetry()) // exhausted

        policy.onSuccess()

        assertEquals(0, policy.failureCount)
        assertTrue("a success must restore the retry budget", policy.onFailureShouldRetry())
    }

    @Test
    fun successWhileRetryPending_marksTheRetryRedundant() {
        val policy = ContactUpdateRetryPolicy()
        assertTrue(policy.onFailureShouldRetry())
        assertTrue("retry is warranted right after the failure", policy.retryStillWarranted)

        // A regular run (ticker / home-screen resume) succeeds while the
        // delayed retry is still pending...
        policy.onSuccess()

        // ...so the pending retry must no-op instead of burning a redundant
        // network pass.
        assertFalse(policy.retryStillWarranted)
    }

    @Test
    fun freshPolicy_hasNoPendingRetryAndNoFailures() {
        val policy = ContactUpdateRetryPolicy()
        assertEquals(0, policy.failureCount)
        assertFalse(policy.retryStillWarranted)
    }

    @Test
    fun externalTriggerFailure_afterExhaustion_staysQuietUntilASuccess() {
        // Budget exhausted; a later EXTERNAL trigger (resume) runs the update
        // and fails again — that must not resurrect the retry loop. Only a
        // success resets it.
        val policy = ContactUpdateRetryPolicy(maxConsecutiveRetries = 2)
        assertTrue(policy.onFailureShouldRetry())
        assertTrue(policy.onFailureShouldRetry())
        assertFalse(policy.onFailureShouldRetry())

        assertFalse("external-trigger failure must not re-arm retries", policy.onFailureShouldRetry())
    }
}
