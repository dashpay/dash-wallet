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

import androidx.paging.PagingSource
import androidx.paging.PagingState
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
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.SearchResult
import javax.inject.Inject

interface ExploreRepository {
    suspend fun getMerchants(): List<Merchant>?
    suspend fun getAtms(): List<Atm>?
    fun observeMerchants(): Flow<List<Merchant>>
    suspend fun searchMerchants(query: String): List<Merchant>?
}

class FirebaseExploreTable @Inject constructor()
    : ExploreRepository, PagingSource<Int, SearchResult>() {

    companion object {
        private const val explorePath = "explore"
        private const val merchantChild = "merchant/data"
        private const val atmChild = "atm/data"
        private const val nameChild = "name"
    }

    private val auth = Firebase.auth
    private var tableRef = Firebase.database.getReference(explorePath)

    override suspend fun getMerchants(): List<Merchant>? {
        ensureAuthenticated()
        return this.tableRef.child(merchantChild).get().await().getValue<List<Merchant>>()
    }

    override suspend fun getAtms(): List<Atm>? {
        ensureAuthenticated()
        val result = this.tableRef.child(atmChild).get().await().getValue<List<Atm>>()
        result?.forEachIndexed { index, atm -> atm.id = index }
        return result
    }

    override fun observeMerchants(): Flow<List<Merchant>> = callbackFlow {
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

    override suspend fun searchMerchants(query: String): List<Merchant>? {
        ensureAuthenticated()
        return tableRef.orderByChild(nameChild)
            .startAt(query)
            .endAt(query + "\uf8ff").get().await().getValue<List<Merchant>>()
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

    override fun getRefreshKey(state: PagingState<Int, SearchResult>): Int? {
        TODO("Not yet implemented")
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchResult> {
        TODO("Not yet implemented")
    }
}