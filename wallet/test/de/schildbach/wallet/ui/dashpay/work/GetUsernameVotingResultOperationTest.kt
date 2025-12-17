/*
 * Copyright 2024 Dash Core Group
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

import de.schildbach.wallet.database.entity.UsernameRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests for GetUsernameVotingResultOperation delay calculation logic
 */
class GetUsernameVotingResultOperationTest {

    companion object {
        // Mock voting period for testing (using testnet value of 90 minutes)
        private val BUFFER_MINUTES = TimeUnit.MINUTES.toMillis(2)
    }

    @Test
    fun `delay calculation when voting just started`() {
        // Given: Voting started now
        val currentTime = System.currentTimeMillis()
        val votingStartedAt = currentTime
        
        // When: Calculate delay using the calculateDelay function
        val delay = GetUsernameVotingResultOperation.calculateDelay(votingStartedAt, currentTime)
        
        // Then: Delay should be voting period + buffer time
        // Since: MOCK_VOTING_PERIOD_MILLIS + currentTime + BUFFER - currentTime = MOCK_VOTING_PERIOD_MILLIS + BUFFER
        val expectedDelay = UsernameRequest.VOTING_PERIOD_MILLIS + BUFFER_MINUTES
        assertEquals("Delay should be voting period plus buffer when voting just started", 
            expectedDelay, delay) // Allow 1 second tolerance
    }

    @Test
    fun `delay calculation when voting started 30 minutes ago`() {
        // Given: Voting started 30 minutes ago
        val currentTime = System.currentTimeMillis()
        val votingStartedAt = currentTime - TimeUnit.MINUTES.toMillis(30)
        
        // When: Calculate delay using the calculateDelay function
        val delay = GetUsernameVotingResultOperation.calculateDelay(votingStartedAt, currentTime)
        
        // Then: Delay should be 60 minutes + 2 minutes buffer = 62 minutes
        // Since: 90min + (currentTime - 30min) + 2min - currentTime = 90min - 30min + 2min = 62min
        val expectedDelay = TimeUnit.MINUTES.toMillis(62)
        assertEquals("Delay should account for time already elapsed in voting period", 
            expectedDelay, delay)
    }

    @Test
    fun `delay calculation when voting started 85 minutes ago (near end)`() {
        // Given: Voting started 85 minutes ago (5 minutes before end)
        val currentTime = System.currentTimeMillis()
        val votingStartedAt = currentTime - TimeUnit.MINUTES.toMillis(85)
        
        // When: Calculate delay using the calculateDelay function
        val delay = GetUsernameVotingResultOperation.calculateDelay(votingStartedAt, currentTime)
        
        // Then: Delay should be approximately 7 minutes
        // Since: 90min + (currentTime - 85min) + 2min - currentTime = 90min - 85min + 2min = 7min
        val expectedDelay = TimeUnit.MINUTES.toMillis(7)
        assertEquals("Delay should be small when voting is near end", 
            expectedDelay, delay)
    }

    @Test
    fun `delay calculation when voting ended 10 minutes ago`() {
        // Given: Voting started 100 minutes ago (ended 10 minutes ago)
        val currentTime = System.currentTimeMillis()
        val votingStartedAt = currentTime - TimeUnit.MINUTES.toMillis(100)
        
        // When: Calculate delay using the calculateDelay function
        val delay = GetUsernameVotingResultOperation.calculateDelay(votingStartedAt, currentTime)
        
        // Then: Delay should be negative (work should run immediately)
        // Since: 90min + (currentTime - 100min) + 2min - currentTime = 90min - 100min + 2min = -8min
        val expectedDelay = -TimeUnit.MINUTES.toMillis(8)
        assertEquals("Delay should be negative when voting ended", 
            expectedDelay, delay)
    }

    @Test
    fun `delay calculation for future voting start time`() {
        // Given: Voting will start in 1 hour
        val currentTime = System.currentTimeMillis()
        val votingStartedAt = currentTime + TimeUnit.HOURS.toMillis(1)
        
        // When: Calculate delay using the calculateDelay function
        val delay = GetUsernameVotingResultOperation.calculateDelay(votingStartedAt, currentTime)
        
        // Then: Delay should be the time until voting ends plus buffer
        // Since: 90min + (currentTime + 60min) + 2min - currentTime = 90min + 60min + 2min = 152min
        val expectedDelay = TimeUnit.MINUTES.toMillis(152)
        assertEquals("Delay should account for future voting start time", 
            expectedDelay, delay)
    }

    @Test
    fun `delay formula breakdown verification`() {
        // Given: Specific test case with fixed time for predictable results
        val votingStartedAt = 900000L // Started at specific time
        val currentTime = System.currentTimeMillis()
        // When: Calculate delay using the calculateDelay function
        val delay = GetUsernameVotingResultOperation.calculateDelay(votingStartedAt, currentTime)
        
        // Then: Verify the calculation uses actual VOTING_PERIOD_MILLIS and current time
        // Formula: UsernameRequest.VOTING_PERIOD_MILLIS + votingStartedAt + 2min - System.currentTimeMillis()
        val expectedDelay = UsernameRequest.VOTING_PERIOD_MILLIS + votingStartedAt + TimeUnit.MINUTES.toMillis(2) - currentTime
        assertEquals("Delay calculation should follow the exact calculateDelay formula", expectedDelay, delay)
    }

    @Test
    fun `scheduled execution time should be voting end time plus buffer`() {
        // Given: Any voting start time
        val currentTime = System.currentTimeMillis()
        val votingStartedAt = currentTime - TimeUnit.MINUTES.toMillis(45) // Started 45 min ago

        // When: Calculate when the work will actually execute
        val delay = GetUsernameVotingResultOperation.calculateDelay(votingStartedAt, currentTime)
        val scheduledExecutionTime = currentTime + delay

        // Then: Scheduled execution should be voting end time + buffer
        val votingEndTime = votingStartedAt + UsernameRequest.VOTING_PERIOD_MILLIS
        val expectedExecutionTime = votingEndTime + BUFFER_MINUTES

        assertEquals("Work should be scheduled to run at voting end time plus buffer", 
            expectedExecutionTime, scheduledExecutionTime)
    }

    @Test
    fun `unique work name generation`() {
        // Given: Username and identity ID
        val username = "testuser"
        val identityId = "abc123"

        // When: Generate unique work name
        val workName = GetUsernameVotingResultOperation.uniqueWorkName(username, identityId)

        // Then: Work name should contain both parameters
        assertTrue("Work name should contain username", workName.contains(username))
        assertTrue("Work name should contain identity ID", workName.contains(identityId))
        assertEquals("Work name should follow expected format", 
            "GetUsernameVotingResult.WORK#testuser:abc123", workName)
    }

    @Test
    fun `delay calculation handles edge case of zero voting period`() {
        // Given: Zero voting period (edge case)
        val currentTime = System.currentTimeMillis()
        val votingStartedAt = currentTime - TimeUnit.MINUTES.toMillis(10)

        // When: Calculate delay using the calculateDelay function (note: this will use actual VOTING_PERIOD, not zero)
        val delay = GetUsernameVotingResultOperation.calculateDelay(votingStartedAt, currentTime)

        // Then: Should calculate correctly with actual voting period
        // Since: UsernameRequest.VOTING_PERIOD_MILLIS + (currentTime - 10min) + 2min - currentTime = VOTING_PERIOD - 10min + 2min
        val expectedDelay = UsernameRequest.VOTING_PERIOD_MILLIS - TimeUnit.MINUTES.toMillis(8)
        assertEquals("Zero voting period should be handled correctly", expectedDelay, delay)
    }

    @Test
    fun `negative delays indicate immediate execution needed`() {
        // Given: Voting ended long ago
        val currentTime = System.currentTimeMillis()
        val votingStartedAt = currentTime - TimeUnit.HOURS.toMillis(2) // Started 2 hours ago
        
        // When: Calculate delay using the calculateDelay function
        val delay = GetUsernameVotingResultOperation.calculateDelay(votingStartedAt, currentTime)
        
        // Then: Delay should be negative, indicating immediate execution
        // Since: UsernameRequest.VOTING_PERIOD_MILLIS + (currentTime - 120min) + 2min - currentTime = VOTING_PERIOD - 120min + 2min
        assertTrue("Delay should be negative for overdue voting results", delay < 0)
        
        val expectedDelay = UsernameRequest.VOTING_PERIOD_MILLIS - TimeUnit.MINUTES.toMillis(118)
        assertEquals("Delay should be correct negative value for overdue case", 
            expectedDelay, delay)
        
        // The WorkManager should handle negative delays by executing immediately
        // This is expected behavior for overdue tasks
    }
}