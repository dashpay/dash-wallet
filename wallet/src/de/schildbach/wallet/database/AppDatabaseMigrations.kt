/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class AppDatabaseMigrations {
    companion object {
        val migration11To12 =
            object : Migration(11, 12) {
                // Migrations for the DashDirect gift cards integration.
                // We save gift cards and merchant icons into the database.
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE transaction_metadata ADD COLUMN customIconId BLOB"
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS icon_bitmaps (id BLOB NOT NULL PRIMARY KEY, " +
                            "imageData BLOB NOT NULL, height INTEGER NOT NULL, width INTEGER NOT NULL)"
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS gift_cards (id TEXT NOT NULL PRIMARY KEY, " +
                            "merchantName TEXT NOT NULL, transactionId BLOB NOT NULL, " +
                            "price INTEGER NOT NULL, currency TEXT NOT NULL, number TEXT NOT NULL, " +
                            "pin TEXT, barcodeValue TEXT, barcodeFormat TEXT, currentBalanceUrl TEXT)"
                    )
                    database.execSQL("CREATE UNIQUE INDEX index_gift_cards_transactionId ON  gift_cards(transactionId)")
                }
            }
    }
}
