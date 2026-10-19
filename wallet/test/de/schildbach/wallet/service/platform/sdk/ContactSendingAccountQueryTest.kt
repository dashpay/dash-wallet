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

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.persistence.entities.AccountEntity
import org.dashfoundation.dashsdk.persistence.entities.CoreAddressEntity
import org.dashfoundation.dashsdk.persistence.entities.DashpayContactRequestEntity
import org.dashfoundation.dashsdk.persistence.entities.IdentityEntity
import org.dashfoundation.dashsdk.persistence.entities.WalletEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [readSdkContactSendingAccount] against a real (in-memory) SDK Room schema:
 * the right request row, the right `dashpayExternalAccount` row and its
 * `core_addresses` are picked, and nothing from another contact leaks in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class ContactSendingAccountQueryTest {

    private lateinit var db: DashDatabase

    private val walletIdHex = "11".repeat(32)
    private val walletId = requireNotNull(walletIdFromHex(walletIdHex))

    private val ourId = ByteArray(32) { 1 }
    private val contactId = ByteArray(32) { 2 }
    private val otherContactId = ByteArray(32) { 3 }

    private val network = 1

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DashDatabase::class.java
        ).allowMainThreadQueries().build()
        db.walletDao().upsert(WalletEntity(walletId = walletId, walletGroupId = walletId))
        db.identityDao().upsert(IdentityEntity(identityId = ourId, networkRaw = network, walletId = walletId))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun incomingRequest(
        from: ByteArray,
        accountReference: Int,
        externalAccountReference: Int?,
        broken: Boolean = false,
        isOutgoing: Boolean = false
    ) = db.dashpayDao().upsertContactRequest(
        DashpayContactRequestEntity(
            networkRaw = network,
            ownerIdentityId = ourId,
            contactIdentityId = from,
            isOutgoing = isOutgoing,
            senderKeyIndex = 0,
            recipientKeyIndex = 0,
            accountReference = accountReference,
            encryptedPublicKey = ByteArray(96),
            coreHeightCreatedAt = 1,
            createdAtMillis = 1,
            paymentChannelBroken = broken,
            externalAccountReference = externalAccountReference
        )
    )

    private suspend fun externalAccount(friend: ByteArray, accountType: Int = 13): Long =
        db.accountDao().insert(
            AccountEntity(
                walletId = walletId,
                accountType = accountType,
                accountIndex = 0,
                accountTypeName = "dashpayExternalAccount",
                userIdentityId = ourId,
                friendIdentityId = friend
            )
        )

    private suspend fun address(accountId: Long, address: String, index: Int, used: Boolean) =
        db.coreAddressDao().upsert(
            CoreAddressEntity(
                address = address,
                poolTypeTag = 2,
                addressIndex = index,
                derivationPath = "m/9'/1'/15'/0'/$index",
                isUsed = used,
                accountId = accountId
            )
        )

    private suspend fun read(loaded: Set<String> = setOf(walletIdHex), database: DashDatabase? = db) =
        readSdkContactSendingAccount(database, loaded, ourId, contactId)

    @Test
    fun readsTheContactsRequestAccountAndPool() = runBlocking {
        incomingRequest(contactId, accountReference = 7, externalAccountReference = 7)
        val accountId = externalAccount(contactId)
        address(accountId, "addr0", 0, used = true)
        address(accountId, "addr1", 1, used = false)
        // Another contact's account and address must not leak in.
        incomingRequest(otherContactId, accountReference = 9, externalAccountReference = 9, broken = true)
        address(externalAccount(otherContactId), "other0", 0, used = false)

        val result = read()

        assertTrue(result?.incomingRequestFound == true)
        assertFalse(result?.paymentChannelBroken == true)
        assertEquals(7, result?.externalAccountReference)
        assertEquals(
            listOf(SdkContactPoolAddress("addr0", 0, true, 2), SdkContactPoolAddress("addr1", 1, false, 2)),
            result?.addresses?.sortedBy { it.index }
        )
    }

    @Test
    fun brokenChannelAndBuildReferenceComeFromTheIncomingRow() = runBlocking {
        // The outgoing row (our request to them) carries nothing about our
        // sending account and must not be read as the incoming one.
        incomingRequest(contactId, accountReference = 1, externalAccountReference = null, isOutgoing = true)
        incomingRequest(contactId, accountReference = 7, externalAccountReference = 5, broken = true)

        val result = read()

        assertTrue(result?.paymentChannelBroken == true)
        assertEquals(5, result?.externalAccountReference)
        assertNull("no external account row", result?.addresses)
    }

    @Test
    fun noIncomingRequest() = runBlocking {
        incomingRequest(contactId, accountReference = 1, externalAccountReference = null, isOutgoing = true)

        val result = read()

        assertFalse(result?.incomingRequestFound == true)
    }

    @Test
    fun onlyTheExternalAccountTypeCounts() = runBlocking {
        incomingRequest(contactId, accountReference = 7, externalAccountReference = 7)
        // dashpayReceivingFunds (12) for the same pair is OUR receiving chain.
        address(externalAccount(contactId, accountType = 12), "ours0", 0, used = false)

        assertNull(read()?.addresses)
    }

    @Test
    fun unavailableWithoutAStartedStoreOrASingleBoundWallet() = runBlocking {
        assertNull(read(database = null))
        assertNull(read(loaded = emptySet()))
        assertNull(read(loaded = setOf(walletIdHex, "22".repeat(32))))
    }
}
