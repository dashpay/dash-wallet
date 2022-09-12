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
        // This will handle the migration from Dash Wallet (db v11) to DashPay (db v17)
        @JvmStatic
        val migration11To17 = object : Migration(11, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE `invitation_table` (`userId` TEXT NOT NULL, `txid` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, `memo` TEXT NOT NULL, `sentAt` INTEGER NOT NULL, `acceptedAt` INTEGER NOT NULL, `shortDynamicLink` TEXT, `dynamicLink` TEXT, PRIMARY KEY(`userId`))")
                database.execSQL("CREATE TABLE `user_alerts` (`stringResId` INTEGER NOT NULL, `iconResId` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`stringResId`))")
                database.execSQL("CREATE TABLE `dashpay_contact_request` (`userId` TEXT NOT NULL, `toUserId` TEXT NOT NULL, `accountReference` INTEGER NOT NULL, `encryptedPublicKey` BLOB NOT NULL, `senderKeyIndex` INTEGER NOT NULL, `recipientKeyIndex` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `encryptedAccountLabel` BLOB, `autoAcceptProof` BLOB, PRIMARY KEY(`userId`, `toUserId`, `accountReference`))")
                database.execSQL("CREATE TABLE `dashpay_profile` (`userId` TEXT NOT NULL, `username` TEXT NOT NULL, `displayName` TEXT NOT NULL, `publicMessage` TEXT NOT NULL, `avatarUrl` TEXT NOT NULL, `avatarHash` BLOB, `avatarFingerprint` BLOB, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`))")
                database.execSQL("CREATE TABLE `blockchain_identity` (`creationState` INTEGER NOT NULL, `creationStateErrorMessage` TEXT, `username` TEXT, `userId` TEXT, `restoring` INTEGER NOT NULL, `identity` BLOB, `creditFundingTxId` BLOB, `usingInvite` INTEGER NOT NULL, `invite` TEXT, `preorderSalt` BLOB, `registrationStatus` INTEGER, `usernameStatus` INTEGER, `creditBalance` INTEGER, `activeKeyCount` INTEGER, `totalKeyCount` INTEGER, `keysCreated` INTEGER, `currentMainKeyIndex` INTEGER, `currentMainKeyType` INTEGER, `id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        // For DashPay Testnet (db v16 to v17)
        // for the case of DashPay Testnet, version 16 added dashpay tables,
        // but v16 lacks metadata tables, so we need to add them here
        @JvmStatic
        val migration16To17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE `transaction_metadata` (`txId` BLOB NOT NULL, `timestamp` INTEGER NOT NULL, `value` INTEGER NOT NULL, `type` TEXT NOT NULL, `taxCategory` TEXT, `currencyCode` TEXT, `rate` TEXT, `memo` TEXT NOT NULL, `service` TEXT, PRIMARY KEY(`txId`))")
                database.execSQL("CREATE TABLE `address_metadata` (`address` TEXT NOT NULL, `isInput` INTEGER NOT NULL, `taxCategory` TEXT NOT NULL, `service` TEXT NOT NULL, PRIMARY KEY(`address`, `isInput`))")
            }
        }
    }
}