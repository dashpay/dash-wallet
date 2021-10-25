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
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

interface ExploreRepository {
    suspend fun getLastUpdate(): Long
    suspend fun getLastUpdate(tableName: String): Long
    suspend fun getDataSize(tableName: String): Int
    suspend fun <T> get(
        tableName: String,
        startAt: Int,
        endBefore: Int,
        valueType: Class<T>
    ): List<T>
}

class FirebaseExploreDatabase @Inject constructor() : ExploreRepository {
    companion object Tables {
        const val DASH_DIRECT_TABLE = "dash_direct"
        const val MERCHANT_TABLE = "dcg_merchant"
        const val ATM_TABLE = "atm"
    }

    private val auth = Firebase.auth
    private val fbDatabase = Firebase.database


    override suspend fun <T> get(
        tableName: String,
        startAt: Int,
        endBefore: Int,
        valueType: Class<T>
    ): List<T> {
        ensureAuthenticated()
        val dataSnapshot = fbDatabase.getReference("explore/$tableName/data")
            .orderByKey()
            .startAt(startAt.toString())
            .endBefore(endBefore.toString())
            .get()
            .await()

        val data = mutableListOf<T>()
        dataSnapshot.children.forEach {
            val merchant = it.getValue(valueType)!!
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