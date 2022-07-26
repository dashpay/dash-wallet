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


class AppDatabaseMigrations {
    companion object {
        // this is a place holder for future migrations post version 11
        // that will need to preserve the transaction_metadata and address_metadata tables
        // If new tables are added in version 12, then we will need something like this:

//        @JvmStatic
//        val migration11To12 = object : Migration(11, 12) {
//            override fun migrate(database: SupportSQLiteDatabase) {
//                database.execSQL("CREATE TABLE `table_one`...")
//                database.execSQL("CREATE TABLE `table_two`...")
//            }
//        }
    }
}