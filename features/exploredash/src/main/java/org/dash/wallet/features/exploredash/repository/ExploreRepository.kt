/*
 * Copyright 2021 Dash Core Group.
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
        const val DCG_MERCHANT_TABLE = "dcg_merchant"
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