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

class AppDatabaseMigrations {
    companion object {
        // v7.4.1 to 7.4.5 (version 8) held Explore Dash Data in these tables
        // In v7.4.6 (version 9), Explore Dash data was moved to a different DB,
        // but the migration to drop these tables was set at 3 to 9 which did not work.
        @JvmStatic
        val migration8To10 = object : Migration(8, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `merchant`")
                database.execSQL("DROP TABLE IF EXISTS `atm`")
                database.execSQL("DROP TABLE IF EXISTS `merchant_fts`")
                database.execSQL("DROP TABLE IF EXISTS `atm_fts`")
            }
        }

        // v7.4.6 (version 9) still had Explore Dash Data in this DB
        @JvmStatic
        val migration9To10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `merchant`")
                database.execSQL("DROP TABLE IF EXISTS `atm`")
                database.execSQL("DROP TABLE IF EXISTS `merchant_fts`")
                database.execSQL("DROP TABLE IF EXISTS `atm_fts`")
            }
        }
    }
}