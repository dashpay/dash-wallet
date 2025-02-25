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
                // Migrations for the CTX gift cards integration.
                // We save gift cards and merchant icons into the database.
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE transaction_metadata ADD COLUMN customIconId BLOB"
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS icon_bitmaps (id BLOB NOT NULL PRIMARY KEY, " +
                            "imageData BLOB NOT NULL, originalUrl TEXT NOT NULL, " +
                            "height INTEGER NOT NULL, width INTEGER NOT NULL)"
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS gift_cards (txId BLOB NOT NULL PRIMARY KEY, " +
                            "merchantName TEXT NOT NULL, price REAL NOT NULL, number TEXT, pin TEXT, " +
                            "barcodeValue TEXT, barcodeFormat TEXT, merchantUrl TEXT, note TEXT)"
                    )
                }
            }

        // This will handle the migration from Dash Wallet (db v12) to DashPay (db v17)
        val migration12To13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE `invitation_table` (`userId` TEXT NOT NULL, `txid` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, `memo` TEXT NOT NULL, `sentAt` INTEGER NOT NULL, `acceptedAt` INTEGER NOT NULL, `shortDynamicLink` TEXT, `dynamicLink` TEXT, PRIMARY KEY(`userId`))")
                database.execSQL("CREATE TABLE `user_alerts` (`stringResId` INTEGER NOT NULL, `iconResId` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`stringResId`))")
                database.execSQL("CREATE TABLE `dashpay_contact_request` (`userId` TEXT NOT NULL, `toUserId` TEXT NOT NULL, `accountReference` INTEGER NOT NULL, `encryptedPublicKey` BLOB NOT NULL, `senderKeyIndex` INTEGER NOT NULL, `recipientKeyIndex` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `encryptedAccountLabel` BLOB, `autoAcceptProof` BLOB, PRIMARY KEY(`userId`, `toUserId`, `accountReference`))")
                database.execSQL("CREATE TABLE `dashpay_profile` (`userId` TEXT NOT NULL, `username` TEXT NOT NULL, `displayName` TEXT NOT NULL, `publicMessage` TEXT NOT NULL, `avatarUrl` TEXT NOT NULL, `avatarHash` BLOB, `avatarFingerprint` BLOB, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`))")
                //database.execSQL("CREATE TABLE `blockchain_identity` (`creationState` INTEGER NOT NULL, `creationStateErrorMessage` TEXT, `username` TEXT, `userId` TEXT, `restoring` INTEGER NOT NULL, `identity` BLOB, `creditFundingTxId` BLOB, `usingInvite` INTEGER NOT NULL, `invite` TEXT, `preorderSalt` BLOB, `registrationStatus` INTEGER, `usernameStatus` INTEGER, `creditBalance` INTEGER, `activeKeyCount` INTEGER, `totalKeyCount` INTEGER, `keysCreated` INTEGER, `currentMainKeyIndex` INTEGER, `currentMainKeyType` INTEGER, `id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                // database.execSQL("CREATE TABLE `transaction_metadata` (`txId` BLOB NOT NULL, `timestamp` INTEGER NOT NULL, `value` INTEGER NOT NULL, `type` TEXT NOT NULL, `taxCategory` TEXT, `currencyCode` TEXT, `rate` TEXT, `memo` TEXT NOT NULL, `service` TEXT, `customIconId` BLOB, PRIMARY KEY(`txId`))")
                //database.execSQL("CREATE TABLE `address_metadata` (`address` TEXT NOT NULL, `isInput` INTEGER NOT NULL, `taxCategory` TEXT NOT NULL, `service` TEXT NOT NULL, PRIMARY KEY(`address`, `isInput`))")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transaction_metadata_cache` (`cacheTimestamp` INTEGER NOT NULL, " +
                        "`txId` BLOB NOT NULL, `sentTimestamp` INTEGER, `taxCategory` TEXT, `currencyCode` TEXT, " +
                        "`rate` TEXT, `memo` TEXT, `service` TEXT, `customIconUrl` TEXT, " +
                        "`giftCardNumber` TEXT, `giftCardPin` TEXT, `merchantName` TEXT, `originalPrice` REAL, " +
                        "`barcodeValue` TEXT, `barcodeFormat` TEXT, `merchantUrl` TEXT, " +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transaction_metadata_platform` (`id` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `txId` BLOB NOT NULL, `sentTimestamp` INTEGER, " +
                        "`taxCategory` TEXT, `currencyCode` TEXT, `rate` REAL, `memo` TEXT, `service` TEXT, " +
                        "`customIconUrl` TEXT, `giftCardNumber` TEXT, `giftCardPin` TEXT, `merchantName` TEXT, " +
                        "`originalPrice` REAL, `barcodeValue` TEXT, `barcodeFormat` TEXT, `merchantUrl` TEXT, " +
                        "PRIMARY KEY(`id`, `txId`))"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS username_requests (`requestId` TEXT NOT NULL PRIMARY KEY,
                        `username` TEXT NOT NULL, `normalizedLabel` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `identity` TEXT NOT NULL,
                        `link` TEXT, `votes` INTEGER NOT NULL, `lockVotes` INTEGER NOT NULL, `isApproved` INTEGER NOT NULL);
                    """
                )
            }
        }

        val migration13to14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `imported_masternode_keys` (
                        `proTxHash` BLOB NOT NULL, 
                        `address` TEXT NOT NULL, 
                        `votingPrivateKey` BLOB NOT NULL, 
                        `votingPublicKey` BLOB NOT NULL, 
                        `votingPubKeyHash` BLOB NOT NULL, 
                        PRIMARY KEY(`proTxHash`)
                    );
                    """
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `username_votes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `username` TEXT NOT NULL, 
                        `identity` TEXT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `timestamp` INTEGER NOT NULL
                    )
                    """
                )
            }

        }

        val migration14to15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `topup_table` (
                        `txId` BLOB NOT NULL, 
                        `toUserId` TEXT NOT NULL, 
                        `workId` TEXT NOT NULL, 
                        `creditedAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`txId`)
                    )
                    """
                )
            }
        }
    }
}
