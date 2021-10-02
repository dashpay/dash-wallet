package de.schildbach.wallet.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class AppDatabaseMigrations {
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE `merchant` (`id` INTEGER NOT NULL, `name` TEXT, " +
                        "`active` INTEGER DEFAULT 1, `plusCode` TEXT, `addDate` TEXT, " +
                        "`updateDate` TEXT, `address1` TEXT, `address2` TEXT, `address3` TEXT, " +
                        "`address4` TEXT, `latitude` REAL, `longitude` REAL, `territory` TEXT, " +
                        "`website` TEXT, `type` TEXT, `logoLocation` TEXT, `paymentMethod` TEXT," +
                        "PRIMARY KEY(`id`))")

                database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `merchant_fts` " +
                        "USING FTS4(`name`, `address1`, `address2`, `address3`, `address4`, " +
                        "`territory`, content=`merchant`)")
            }
        }
    }
}