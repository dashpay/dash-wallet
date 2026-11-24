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
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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

/** The saved identity must keep its preorder salts and never report its primary name as a second name. */
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
    fun replacementName_dropsEverythingSavedForTheRejectedName() = runBlocking {
        config.insert(identity("dashuser", "dashuser3").apply {
            creationState = IdentityCreationState.USERNAME_REGISTERING
            creationStateErrorMessage = "DuplicateUniqueIndexError"
            preorderSalt = salt
            preorderSaltSecondary = secondarySalt
            usernameStatus = UsernameStatus.PREORDERED
            usernameSecondaryStatus = UsernameStatus.CONFIRMED
        })

        config.replaceUsername("dashuser2")

        val loaded = config.load()!!
        assertEquals("dashuser2", loaded.username)
        assertNull(loaded.usernameSecondary)
        assertNull(loaded.preorderSalt)
        assertNull(loaded.preorderSaltSecondary)
        assertNull(loaded.usernameStatus)
        assertNull(loaded.usernameSecondaryStatus)
        assertEquals(IdentityCreationState.IDENTITY_REGISTERED, loaded.creationState)
        assertNull(loaded.creationStateErrorMessage)
        assertEquals("userId", loaded.userId)
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

    private fun identity(username: String, usernameSecondary: String?) = BlockchainIdentityData(
        IdentityCreationState.IDENTITY_REGISTERED, null, username, usernameSecondary, "userId", false
    )
}
