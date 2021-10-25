/*
 * Copyright 2021 Dash Core Group
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

package org.dash.wallet.features.exploredash.repository

import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.slf4j.LoggerFactory
import javax.inject.Inject

const val DASH_DIRECT_TABLE = "dash_direct"
const val DCG_MERCHANT_TABLE = "dcg_merchant"
const val ATM_TABLE = "atm"

private val log = LoggerFactory.getLogger(FirebaseMerchantTable::class.java)

interface MerchantRepository {
    suspend fun get(): List<Merchant>?
    fun observe(): Flow<List<Merchant>>
    suspend fun search(query: String): List<Merchant>?

    suspend fun getLastUpdate(): Long
    suspend fun getLastUpdate(tableName: String): Long
    suspend fun getDataSize(tableName: String): Int
    suspend fun get(tableName: String, startAt: Int, endBefore: Int): List<Merchant>
}

class FirebaseMerchantTable @Inject constructor() : MerchantRepository {

    companion object {
        private const val merchantPath = "explore/merchant"
        private const val nameChild = "name"
    }

    private val auth = Firebase.auth
    private val fbDatabase = Firebase.database
    private var tableRef = Firebase.database.getReference(merchantPath)

    override suspend fun get(): List<Merchant>? {
        ensureAuthenticated()
        return this.tableRef.get().await().getValue<List<Merchant>>()
    }

    override fun observe(): Flow<List<Merchant>> = callbackFlow {
        ensureAuthenticated()
        val callback = object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                val client = dataSnapshot.getValue<List<Merchant>>()
                client?.let { trySend(it) }
            }

            override fun onCancelled(error: DatabaseError) {
                cancel("Database Error", error.toException())
            }
        }

        tableRef.addValueEventListener(callback)
        awaitClose { tableRef.removeEventListener(callback) }
    }

    override suspend fun search(query: String): List<Merchant>? {
        return tableRef.orderByChild(nameChild)
            .startAt(query)
            .endAt(query + "\uf8ff").get().await().getValue<List<Merchant>>()
    }

    override suspend fun get(tableName: String, startAt: Int, endBefore: Int): List<Merchant> {
        ensureAuthenticated()
        val dataSnapshot = fbDatabase.getReference("explore/$tableName/data")
            .orderByKey()
            .startAt(startAt.toString())
            .endBefore(endBefore.toString())
            .get()
            .await()

        val data = mutableListOf<Merchant>()
        dataSnapshot.children.forEach {
            val merchant = it.getValue(Merchant::class.java)!!
            data.add(merchant)
        }
        return data
    }

    override suspend fun getDataSize(tableName: String): Int {
        ensureAuthenticated()
        return fbDatabase.getReference("explore/$tableName/data_size")
            .get()
            .await()
            .getValue<Int>()!!
    }

    override suspend fun getLastUpdate(): Long {
        ensureAuthenticated()
        return fbDatabase.getReference("explore/last_update")
            .get()
            .await()
            .getValue<Long>()!!
    }

    override suspend fun getLastUpdate(tableName: String): Long {
        ensureAuthenticated()
        return fbDatabase.getReference("explore/$tableName/last_update")
            .get()
            .await()
            .getValue<Long>()!!
    }

    private suspend fun ensureAuthenticated() {
        if (auth.currentUser == null) {
            signingAnonymously()
        }
    }

    private suspend fun signingAnonymously(): FirebaseUser {
        val result = auth.signInAnonymously().await()
        return result.user ?: throw FirebaseAuthException("-1", "User is null after anon sign in")
    }
}