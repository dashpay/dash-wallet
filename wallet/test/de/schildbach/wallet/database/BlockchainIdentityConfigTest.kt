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

package de.schildbach.wallet.database

import android.app.Application
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.PREORDER_SALT_SECONDARY
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.USERNAME
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.USERNAME_SECONDARY
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.USERNAME_SECONDARY_REGISTRATION_STATUS
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DuplicateSecondaryUsernameMigration
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.NameReplacementAttempt
import de.schildbach.wallet.database.entity.NameReplacementFailure
import de.schildbach.wallet.database.entity.NameReplacementState
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.dashj.platform.dashpay.UsernameStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The saved identity keeps its preorder salts, and a name replacement attempt only moves along its own transitions. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class BlockchainIdentityConfigTest {
    private val config = BlockchainIdentityConfig(
        ApplicationProvider.getApplicationContext<Application>(),
        mockk(relaxed = true),
        mockk(relaxed = true)
    )
    private val salt = ByteArray(32) { 3 }
    private val secondarySalt = ByteArray(32) { 5 }

    companion object {
        private const val REJECTED = "StateTransitionBroadcastException: DuplicateUniqueIndexError"
        private val KEY = ByteArray(20) { 7 }
        private val TX = ByteArray(40) { 9 }
    }

    @Test
    fun savedIdentity_keepsItsPreorderSalts() = runBlocking {
        config.insert(identity("Dashuser", "dashuser2").apply {
            preorderSalt = salt
            preorderSaltSecondary = secondarySalt
        })

        val loaded = config.load()!!
        assertArrayEquals(salt, loaded.preorderSalt)
        assertArrayEquals(secondarySalt, loaded.preorderSaltSecondary)
        assertEquals("dashuser2", loaded.usernameSecondary)
    }

    @Test
    fun secondaryNameEqualToThePrimary_isNotReported() = runBlocking {
        config.insert(identity("dashuser", "dashuser"))

        assertNull(config.load()!!.usernameSecondary)
        assertNull(config.loadBase().usernameSecondary)
    }

    @Test
    fun approval_movesTheRejectedErrorIntoTheAttempt() = runBlocking {
        config.insert(rejectedInviteIdentity())

        assertTrue(config.approveReplacement(attempt("a")))

        val saved = config.loadReplacement()!!
        assertEquals(NameReplacementState.APPROVED, saved.state)
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, saved.rejectedState)
        assertEquals(REJECTED, saved.rejectedError)
        assertNull(config.load()!!.creationStateErrorMessage)
        assertEquals("dashuser", config.load()!!.username)
    }

    @Test
    fun approval_isRefusedWhileAnAttemptHoldsFundsOrAnUnfinishedName() = runBlocking {
        config.insert(rejectedInviteIdentity())
        assertTrue(config.approveReplacement(attempt("a", Coin.parseCoin("0.03"))))

        assertFalse(config.approveReplacement(attempt("b")))
        config.markReplacementDispatching("a", KEY)
        config.failReplacement("a", NameReplacementFailure.RETRYABLE, "offline")
        assertFalse(config.approveReplacement(attempt("b")))
        assertEquals("a", config.loadReplacement()!!.id)
    }

    @Test
    fun approval_replacesAFinalAttemptAndKeepsTheOriginalRejection() = runBlocking {
        config.insert(rejectedInviteIdentity())
        config.approveReplacement(attempt("a", Coin.parseCoin("0.03")))
        config.markReplacementDispatching("a", KEY)
        config.failReplacement("a", NameReplacementFailure.FINAL, "InsufficientMoneyException")

        assertTrue(config.approveReplacement(attempt("b")))

        val saved = config.loadReplacement()!!
        assertEquals("b", saved.id)
        assertEquals(NameReplacementState.APPROVED, saved.state)
        assertNull(saved.failure)
        assertNull(saved.topUpKeyHash)
        assertEquals(REJECTED, saved.rejectedError)
    }

    @Test
    fun approval_isRefusedForAnIdentityItDoesNotApplyTo() = runBlocking {
        config.insert(rejectedInviteIdentity().apply { usingInvite = false })
        assertFalse(config.approveReplacement(attempt("a")))

        config.insert(rejectedInviteIdentity())
        assertFalse(config.approveReplacement(attempt("a").copy(identityId = "otherId")))

        config.insert(rejectedInviteIdentity().apply { creationState = IdentityCreationState.USERNAME_REGISTERED })
        assertFalse(config.approveReplacement(attempt("a")))

        config.insert(rejectedInviteIdentity().apply { usernameSecondary = "dashuser3" })
        assertFalse(config.approveReplacement(attempt("a")))
        assertNull(config.loadReplacement())
    }

    @Test
    fun approval_isRefusedOnceTheRejectionIsGone() = runBlocking {
        config.insert(rejectedInviteIdentity().apply { creationStateErrorMessage = null })

        assertFalse(config.approveReplacement(attempt("a")))
        assertNull(config.loadReplacement())
    }

    @Test
    fun finalFailure_afterTheIdentityChanged_leavesTheIdentityAlone() = runBlocking {
        config.insert(rejectedInviteIdentity())
        config.approveReplacement(attempt("a"))
        config.updateCreationState(IdentityCreationState.DONE, null)

        config.failReplacement("a", NameReplacementFailure.FINAL, "stop")

        assertEquals(IdentityCreationState.DONE, config.load()!!.creationState)
        assertNull(config.load()!!.creationStateErrorMessage)
    }

    @Test
    fun savedName_afterTheIdentityChanged_dropsTheAttemptInstead() = runBlocking {
        config.insert(rejectedInviteIdentity())
        config.approveReplacement(attempt("a"))
        config.updateCreationState(IdentityCreationState.VOTING, null)

        assertNull(config.saveReplacementName("a", NameReplacementState.APPROVED))

        assertNull(config.loadReplacement())
        assertEquals("dashuser", config.load()!!.username)
        assertEquals(IdentityCreationState.VOTING, config.load()!!.creationState)
    }

    @Test
    fun transitions_runOnlyFromTheirStateForTheirAttempt() = runBlocking {
        config.insert(rejectedInviteIdentity())
        config.approveReplacement(attempt("a", Coin.parseCoin("0.03")))

        assertNull(config.markReplacementDispatched("a", TX))
        assertNull(config.markReplacementDispatching("b", KEY))
        assertArrayEquals(KEY, config.markReplacementDispatching("a", KEY)!!.topUpKeyHash)
        assertNull(config.markReplacementDispatching("a", KEY))
        config.failReplacement("a", NameReplacementFailure.RETRYABLE, "offline")
        assertNull(config.markReplacementDispatched("a", TX))
        config.clearReplacementFailure("a")
        val dispatched = config.markReplacementDispatched("a", TX)!!
        assertEquals(NameReplacementState.DISPATCHED, dispatched.state)
        assertArrayEquals(TX, config.loadReplacement()!!.topUpTx)
        assertArrayEquals(KEY, config.loadReplacement()!!.topUpKeyHash)
    }

    @Test
    fun savedName_withSkip_dropsTheRejectedNameAndItsLinkInOneEdit() = runBlocking {
        config.insert(rejectedInviteIdentity().apply {
            preorderSalt = salt
            usernameStatus = UsernameStatus.PREORDERED
            verificationLink = "https://example.com/old"
        })
        config.approveReplacement(attempt("a"))

        val saved = config.saveReplacementName("a", NameReplacementState.APPROVED)!!

        assertEquals(NameReplacementState.NAME_SAVED, saved.state)
        val loaded = config.load()!!
        assertEquals("dashuser2", loaded.username)
        assertNull(loaded.usernameSecondary)
        assertNull(loaded.preorderSalt)
        assertNull(loaded.usernameStatus)
        assertNull(loaded.verificationLink)
        assertEquals(IdentityCreationState.IDENTITY_REGISTERED, loaded.creationState)
        assertNull(loaded.creationStateErrorMessage)
        assertEquals("userId", loaded.userId)
    }

    @Test
    fun savedName_withAVerifiedLink_savesThatLink() = runBlocking {
        config.insert(rejectedInviteIdentity().apply { verificationLink = "https://example.com/old" })
        config.approveReplacement(attempt("a").copy(verificationLink = "https://example.com/new"))

        config.saveReplacementName("a", NameReplacementState.APPROVED)

        assertEquals("https://example.com/new", config.load()!!.verificationLink)
    }

    @Test
    fun finalFailure_beforeTheName_restoresTheRejectedNameForTheForm() = runBlocking {
        config.insert(rejectedInviteIdentity())
        config.approveReplacement(attempt("a", Coin.parseCoin("0.03")))
        config.markReplacementDispatching("a", KEY)

        assertTrue(config.failReplacement("a", NameReplacementFailure.FINAL, "InsufficientMoneyException"))

        val loaded = config.load()!!
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, loaded.creationState)
        assertEquals(REJECTED, loaded.creationStateErrorMessage)
        assertEquals("dashuser", loaded.username)
        assertEquals(NameReplacementFailure.FINAL, config.loadReplacement()!!.failure)
    }

    @Test
    fun finalFailure_afterTheName_showsTheNewNamesRejection() = runBlocking {
        config.insert(rejectedInviteIdentity())
        config.approveReplacement(attempt("a"))
        config.saveReplacementName("a", NameReplacementState.APPROVED)
        config.updateCreationState(IdentityCreationState.USERNAME_REGISTERING, null)

        config.failReplacement("a", NameReplacementFailure.FINAL, "DuplicateUniqueIndexError: dashuser2")

        val loaded = config.load()!!
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, loaded.creationState)
        assertEquals("DuplicateUniqueIndexError: dashuser2", loaded.creationStateErrorMessage)
        assertEquals("dashuser2", loaded.username)
    }

    @Test
    fun retryableFailure_keepsTheRejectionOnTheAttemptUntilCleared() = runBlocking {
        config.insert(rejectedInviteIdentity())
        config.approveReplacement(attempt("a"))

        config.failReplacement("a", NameReplacementFailure.RETRYABLE, "IOException: offline")

        assertEquals("IOException: offline", config.load()!!.creationStateErrorMessage)
        assertEquals(REJECTED, config.loadReplacement()!!.rejectedError)
        assertEquals(IdentityCreationState.USERNAME_REGISTERING, config.load()!!.creationState)
        assertNull(config.clearReplacementFailure("b"))
        assertNull(config.clearReplacementFailure("a")!!.failure)
        assertNull(config.load()!!.creationStateErrorMessage)
    }

    @Test
    fun completion_removesOnlyItsOwnAttempt() = runBlocking {
        config.insert(rejectedInviteIdentity())
        config.approveReplacement(attempt("a"))

        config.completeReplacement("b")
        assertEquals("a", config.loadReplacement()!!.id)
        config.completeReplacement("a")
        assertNull(config.loadReplacement())
    }

    @Test
    fun migration_removesASavedDuplicateOfThePrimaryName() = runBlocking {
        val duplicate = preferencesOf(
            USERNAME to "dashuser",
            USERNAME_SECONDARY to "dashuser",
            USERNAME_SECONDARY_REGISTRATION_STATUS to UsernameStatus.CONFIRMED.name,
            PREORDER_SALT_SECONDARY to "03"
        )

        assertTrue(DuplicateSecondaryUsernameMigration.shouldMigrate(duplicate))
        val migrated = DuplicateSecondaryUsernameMigration.migrate(duplicate)
        assertEquals("dashuser", migrated[USERNAME])
        assertNull(migrated[USERNAME_SECONDARY])
        assertNull(migrated[USERNAME_SECONDARY_REGISTRATION_STATUS])
        assertNull(migrated[PREORDER_SALT_SECONDARY])
    }

    @Test
    fun migration_keepsARealSecondaryName() = runBlocking {
        assertFalse(
            DuplicateSecondaryUsernameMigration.shouldMigrate(
                preferencesOf(USERNAME to "Dashuser", USERNAME_SECONDARY to "dashuser2")
            )
        )
        assertFalse(DuplicateSecondaryUsernameMigration.shouldMigrate(preferencesOf(USERNAME to "dashuser")))
    }

    private fun rejectedInviteIdentity() = BlockchainIdentityData(
        IdentityCreationState.USERNAME_REGISTERING, REJECTED, "dashuser", null, "userId", false, usingInvite = true
    )

    private fun attempt(id: String, topUp: Coin = Coin.ZERO) =
        NameReplacementAttempt(id, "userId", "dashuser2", null, topUp)

    private fun identity(username: String, usernameSecondary: String?) = BlockchainIdentityData(
        IdentityCreationState.IDENTITY_REGISTERED, null, username, usernameSecondary, "userId", false
    )
}
