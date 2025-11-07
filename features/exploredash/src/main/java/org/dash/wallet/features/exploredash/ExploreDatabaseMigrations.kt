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

package org.dash.wallet.features.exploredash

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class ExploreDatabaseMigrations {
    companion object {
        val migration1To2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Change `merchantId` and `sourceId` in `merchant` table
                    // column types from INTEGER to TEXT.

                    db.execSQL(
                        "CREATE TABLE `temp` AS SELECT " +
                            "deeplink, " +
                            "plusCode, " +
                            "addDate, " +
                            "updateDate, " +
                            "paymentMethod, " +
                            "CAST(merchantId AS TEXT) AS merchantId, " +
                            "id, " +
                            "active, " +
                            "name, " +
                            "address1, " +
                            "address2, " +
                            "address3, " +
                            "address4, " +
                            "latitude, " +
                            "longitude, " +
                            "website, " +
                            "phone, " +
                            "territory, " +
                            "city, " +
                            "source, " +
                            "CAST(sourceId AS TEXT) AS sourceId, " +
                            "logoLocation, " +
                            "googleMaps, " +
                            "coverImage, " +
                            "type " +
                            "FROM merchant"
                    )
                    db.execSQL(
                        "DROP TABLE merchant"
                    )
                    db.execSQL(
                        "CREATE TABLE merchant (" +
                            "deeplink TEXT, " +
                            "plusCode TEXT, " +
                            "addDate TEXT, " +
                            "updateDate TEXT, " +
                            "paymentMethod TEXT, " +
                            "merchantId TEXT, " +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "active INTEGER DEFAULT 1, " +
                            "name TEXT, " +
                            "address1 TEXT, " +
                            "address2 TEXT, " +
                            "address3 TEXT, " +
                            "address4 TEXT, " +
                            "latitude REAL, " +
                            "longitude REAL, " +
                            "website TEXT, " +
                            "phone TEXT, " +
                            "territory TEXT, " +
                            "city TEXT, " +
                            "source TEXT, " +
                            "sourceId TEXT, " +
                            "logoLocation TEXT, " +
                            "googleMaps TEXT, " +
                            "coverImage TEXT, " +
                            "type TEXT " +
                            ")"
                    )
                    db.execSQL(
                        "CREATE INDEX index_merchant_latitude ON merchant (latitude ASC)"
                    )
                    db.execSQL(
                        "CREATE INDEX index_merchant_longitude ON merchant (longitude ASC)"
                    )
                    db.execSQL(
                        "INSERT INTO merchant SELECT * FROM `temp`"
                    )
                    db.execSQL(
                        "DROP TABLE `temp`"
                    )

                    // Change `sourceId` in `atm` table column type from
                    // INTEGER to TEXT.

                    db.execSQL(
                        "CREATE TABLE `temp` AS SELECT " +
                            "postcode, " +
                            "manufacturer, " +
                            "id, " +
                            "active, " +
                            "name, " +
                            "address1, " +
                            "address2, " +
                            "address3, " +
                            "address4, " +
                            "latitude, " +
                            "longitude, " +
                            "website, " +
                            "phone, " +
                            "territory, " +
                            "city, " +
                            "source, " +
                            "CAST(sourceId AS TEXT) AS sourceId, " +
                            "logoLocation, " +
                            "googleMaps, " +
                            "coverImage, " +
                            "type " +
                            "FROM atm"
                    )
                    db.execSQL(
                        "DROP TABLE atm"
                    )
                    db.execSQL(
                        "CREATE TABLE atm (" +
                            "postcode TEXT, " +
                            "manufacturer TEXT, " +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "active INTEGER DEFAULT 1, " +
                            "name TEXT, " +
                            "address1 TEXT, " +
                            "address2 TEXT, " +
                            "address3 TEXT, " +
                            "address4 TEXT, " +
                            "latitude REAL, " +
                            "longitude REAL, " +
                            "website TEXT, " +
                            "phone TEXT, " +
                            "territory TEXT, " +
                            "city TEXT, " +
                            "source TEXT, " +
                            "sourceId TEXT, " +
                            "logoLocation TEXT, " +
                            "googleMaps TEXT, " +
                            "coverImage TEXT, " +
                            "type TEXT " +
                            ")"
                    )
                    db.execSQL(
                        "CREATE INDEX index_atm_latitude ON atm (latitude ASC)"
                    )
                    db.execSQL(
                        "CREATE INDEX index_atm_longitude ON atm (longitude ASC)"
                    )
                    db.execSQL(
                        "INSERT INTO atm SELECT * FROM `temp`"
                    )
                    db.execSQL(
                        "DROP TABLE `temp`"
                    )
                    db.execSQL(
                        "ALTER TABLE merchant ADD COLUMN redeemType TEXT DEFAULT 'barcode'"
                    )
                    db.execSQL(
                        "ALTER TABLE merchant ADD COLUMN savingsPercentage INTEGER DEFAULT 0"
                    )
                }
            }

        val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) { }
        }

        val migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) { }
        }
    }
}
