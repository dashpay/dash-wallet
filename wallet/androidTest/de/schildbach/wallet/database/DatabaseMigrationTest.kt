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

package de.schildbach.wallet.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.transactions.TransactionCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException


@RunWith(AndroidJUnit4::class)
open class DatabaseMigrationTest {
    companion object {
        private const val TEST_DB_NAME = "test_database"
        private const val BLOCKCHAIN_HEIGHT = 587680
        private const val EXCHANGE_RATE = "31438.8212"

        private val txMetadata = TransactionMetadata(
            Sha256Hash.ZERO_HASH,
            System.currentTimeMillis() / 1000,
            Coin.CENT,
            TransactionCategory.Sent,
            TaxCategory.TransferOut,
            "USD",
            "45.99",
            "Pizza for Bob's party",
            ""
        )
        private val address = "yNo1YJcNBoveEHWB7eYmxFZBVEAYQo46Yb"
        private val service = ServiceName.CrowdNode
    }

    private val migrations = arrayOf(
        AppDatabaseMigrations.migration11To12,
        AppDatabaseMigrations.migration12To17,
        AppDatabaseMigrations.migration17To18
    )

    @Rule
    @JvmField
    val testHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Create db and fill with data
        testHelper.createDatabase(TEST_DB_NAME, 1).apply {
            val values = ContentValues()
            values.put("currencyCode", "ARS")
            values.put("rate", EXCHANGE_RATE)
            this.insert("exchange_rates", SQLiteDatabase.CONFLICT_REPLACE, values)

            close()
        }

        // Run migrations
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java, TEST_DB_NAME
        ).addMigrations(*migrations)
            .fallbackToDestructiveMigrationFrom(1,2, 3, 4, 5,6, 7,8 ,9, 10)
            .build()

        // Check that data is valid
        runBlocking {
            val savedRate = db.exchangeRatesDao().getRateSync("ARS")
            assert(savedRate == null)
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate11AndUp() {
        // Create db and fill with data
        testHelper.createDatabase(TEST_DB_NAME, 11).apply {
            var values = ContentValues()
            values.put("id", 1)
            values.put("bestChainDate", 1633356847000)
            values.put("bestChainHeight", BLOCKCHAIN_HEIGHT)
            values.put("replaying", 0)
            values.put("impediments", "")
            values.put("chainlockHeight", 0)
            values.put("mnlistHeight", 587091)
            values.put("percentageSync", 100)
            this.insert("blockchain_state", SQLiteDatabase.CONFLICT_REPLACE, values)

            values = ContentValues()
            values.put("currencyCode", "ARS")
            values.put("rate", EXCHANGE_RATE)
            this.insert("exchange_rates", SQLiteDatabase.CONFLICT_REPLACE, values)

            values = ContentValues()
            values.put("txId", txMetadata.txId.bytes)
            values.put("timestamp", txMetadata.timestamp)
            values.put("type", txMetadata.type.toString())
            values.put("taxCategory", txMetadata.taxCategory!!.toString())
            values.put("value", txMetadata.value.longValue())
            values.put("rate", txMetadata.rate)
            values.put("currencyCode", txMetadata.currencyCode)
            values.put("memo", txMetadata.memo)
            values.put("service", txMetadata.service)
            this.insert("transaction_metadata", SQLiteDatabase.CONFLICT_REPLACE, values)

            values = ContentValues()
            values.put("address", address)
            values.put("isInput", false)
            values.put("taxCategory", TaxCategory.TransferIn.toString())
            values.put("service", service)
            this.insert("address_metadata", SQLiteDatabase.CONFLICT_REPLACE, values)

            close()
        }

        // Run migrations
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java, TEST_DB_NAME
        ).addMigrations(*migrations).build()

        // Check that data is valid
        runBlocking {
            val savedBlockchainState = db.blockchainStateDao().getState()
            assert(savedBlockchainState!!.bestChainHeight == BLOCKCHAIN_HEIGHT)

            val savedRate = db.exchangeRatesDao().getRateSync("ARS")
            assert(savedRate?.rate == EXCHANGE_RATE)

            val savedAddressMetadata = db.addressMetadataDao().loadRecipient(address)
            assert(savedAddressMetadata?.address == address)
            assert(savedAddressMetadata?.service == service)

            val savedTxMetadata = db.transactionMetadataDao().load(txMetadata.txId)!!
            assert(savedTxMetadata == txMetadata)
        }

        db.close()
    }
}
