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
import org.dash.wallet.features.exploredash.repository.model.Merchant
import java.util.*
import javax.inject.Inject


interface MerchantRepository {
    suspend fun get(): List<Merchant>?
    fun observe(): Flow<List<Merchant>>
    suspend fun search(query: String): List<Merchant>?
}

class FirebaseMerchantTable @Inject constructor() : MerchantRepository {
    companion object {
        private const val merchantPath = "explore/merchant"
        private const val nameChild = "name"
    }

    private val auth = Firebase.auth
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