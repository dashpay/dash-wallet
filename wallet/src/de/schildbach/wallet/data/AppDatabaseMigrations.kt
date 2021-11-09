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

package de.schildbach.wallet.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.firebase.database.PropertyName

class AppDatabaseMigrations {
    companion object {
        val migration2To3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE `merchant` (`id` INTEGER NOT NULL, `name` TEXT, " +
                        "`active` INTEGER DEFAULT 1, `plusCode` TEXT, `addDate` TEXT, " +
                        "`updateDate` TEXT, `address1` TEXT, `address2` TEXT, `address3` TEXT, " +
                        "`address4` TEXT, `latitude` REAL, `longitude` REAL, `territory` TEXT, " +
                        "`phone` TEXT, `website` TEXT, `type` TEXT, `logoLocation` TEXT, " +
                        "`deeplink` TEXT, `paymentMethod` TEXT, `source` TEXT, `sourceId` INTEGER, " +
                        "`coverImage` TEXT, `city` TEXT, PRIMARY KEY(`id`))")

                database.execSQL("CREATE TABLE `atm` (`id` INTEGER NOT NULL, `name` TEXT, " +
                        "`active` INTEGER DEFAULT 1, `city` TEXT, `coverImage` TEXT, `phone` TEXT, " +
                        "`postcode` TEXT, `website` TEXT, `type` TEXT, `manufacturer` TEXT, " +
                        "`latitude` REAL, `longitude` REAL, `territory` TEXT, `address1` TEXT, " +
                        "`address2` TEXT, `address3` TEXT, `address4` TEXT, `logoLocation` TEXT, " +
                        "`source` TEXT, `sourceId` INTEGER, PRIMARY KEY(`id`))")


                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `merchant_fts` " +
                        "USING FTS4(`name`, `address1`, `address2`, `address3`, `address4`, " +
                        "`territory`, `city`, content=`merchant`)")

                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `atm_fts` " +
                        "USING FTS4(`name`, `manufacturer`, `address1`, `address2`, `address3`, " +
                        "`address4`, `city`, `territory`, content=`atm`)")
            }
        }
    }
}