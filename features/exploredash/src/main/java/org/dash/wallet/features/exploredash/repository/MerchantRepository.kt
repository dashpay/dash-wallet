package org.dash.wallet.features.exploredash.repository


import android.app.AuthenticationRequiredException
import android.util.Log
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
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
import org.dash.wallet.features.exploredash.repository.model.Merchant
import javax.inject.Inject
import com.google.firebase.auth.FirebaseUser

import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.suspendCancellableCoroutine


interface MerchantRepository {
    suspend fun get(): List<Merchant>?
    fun observe(): Flow<List<Merchant>>
}

class FirebaseMerchantTable @Inject constructor() : MerchantRepository {
    companion object {
        private const val merchantPath = "explore/merchant"
    }

    private val auth = Firebase.auth
    private var tableRef = Firebase.database.getReference(merchantPath)

//    init {
//        ensureAuthenticated()
//    }

    override suspend fun get(): List<Merchant>? {
        ensureAuthenticated()
        return this.tableRef.get().await().getValue<List<Merchant>>()
    }

    override fun observe(): Flow<List<Merchant>> = callbackFlow {
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