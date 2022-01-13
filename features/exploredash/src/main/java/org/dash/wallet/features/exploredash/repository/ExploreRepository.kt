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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.Exception
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        val query = fbDatabase.getReference("explore/$tableName/data")
            .orderByKey()
            .startAt(startAt.toString())
            .endBefore(endBefore.toString())

        return suspendCancellableCoroutine { coroutine ->
            query.addListenerForSingleValueEvent(object: ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    val data = mutableListOf<T>()

                    try {
                        dataSnapshot.children.forEach {
                            val merchant = it.getValue(valueType)!!
                            data.add(merchant)
                        }

                        if (coroutine.isActive) {
                            coroutine.resume(data)
                        }
                    } catch (ex: Exception) {
                        if (coroutine.isActive) {
                            coroutine.resumeWithException(ex)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(error.toException())
                    }
                }
            })
        }
    }

    override suspend fun getDataSize(tableName: String): Int {
        ensureAuthenticated()
        val query = fbDatabase.getReference("explore/$tableName/data_size")

        return suspendCancellableCoroutine { coroutine ->
            query.addListenerForSingleValueEvent(object: ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    if (coroutine.isActive) {
                        try {
                            coroutine.resume(dataSnapshot.getValue<Int>()!!)
                        } catch (ex: Exception) {
                            coroutine.resumeWithException(ex)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(error.toException())
                    }
                }
            })
        }
    }

    override suspend fun getLastUpdate(): Long {
        ensureAuthenticated()
        val query = fbDatabase.getReference("explore/last_update")

        return suspendCancellableCoroutine { coroutine ->
            query.addListenerForSingleValueEvent(object: ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    if (coroutine.isActive) {
                        try {
                            coroutine.resume(dataSnapshot.getValue<Long>()!!)
                        } catch (ex: Exception) {
                            coroutine.resumeWithException(ex)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(error.toException())
                    }
                }
            })
        }
    }

    override suspend fun getLastUpdate(tableName: String): Long {
        ensureAuthenticated()
        val query = fbDatabase.getReference("explore/$tableName/last_update")

        return suspendCancellableCoroutine { coroutine ->
            query.addListenerForSingleValueEvent(object: ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    if (coroutine.isActive) {
                        try {
                            coroutine.resume(dataSnapshot.getValue<Long>()!!)
                        } catch (ex: Exception) {
                            coroutine.resumeWithException(ex)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(error.toException())
                    }
                }
            })
        }
    }

    private suspend fun ensureAuthenticated() {
        if (auth.currentUser == null) {
            signingAnonymously()
        }
    }

    private suspend fun signingAnonymously(): FirebaseUser {
        return suspendCancellableCoroutine { coroutine ->
            auth.signInAnonymously().addOnSuccessListener { result ->
                if (coroutine.isActive) {
                    val user = result.user

                    if (user != null) {
                        coroutine.resume(user)
                    } else {
                        coroutine.resumeWithException(FirebaseAuthException("-1", "User is null after anon sign in"))
                    }
                }
            }.addOnFailureListener {
                if (coroutine.isActive) {
                    coroutine.resumeWithException(it)
                }
            }
        }
    }
}