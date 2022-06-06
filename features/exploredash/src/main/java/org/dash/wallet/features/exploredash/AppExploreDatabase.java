/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.features.exploredash;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.sqlite.db.SupportSQLiteDatabase;

import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.data.RoomConverters;
import org.dash.wallet.features.exploredash.data.AtmDao;
import org.dash.wallet.features.exploredash.data.MerchantDao;
import org.dash.wallet.features.exploredash.data.model.Atm;
import org.dash.wallet.features.exploredash.data.model.AtmFTS;
import org.dash.wallet.features.exploredash.data.model.Merchant;
import org.dash.wallet.features.exploredash.data.model.MerchantFTS;
import org.dash.wallet.features.exploredash.repository.ExploreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

@Database(entities = {
        Merchant.class,
        MerchantFTS.class,
        Atm.class,
        AtmFTS.class
}, version = 1)
@TypeConverters({RoomConverters.class})
public abstract class AppExploreDatabase extends RoomDatabase {

    private static final Logger log = LoggerFactory.getLogger(AppExploreDatabase.class);

    private static AppExploreDatabase instance;

    public abstract MerchantDao merchantDao();

    public abstract AtmDao atmDao();

    public static AppExploreDatabase getAppDatabase(
        Context context,
        Configuration config,
        ExploreRepository repository
    ) {
        if (instance == null) {
            instance = create(context, config, repository);
        }
        return instance;
    }

    private static AppExploreDatabase create(
        Context context,
        Configuration config,
        ExploreRepository repository
    ) {
        String exploreDatabaseName = config.getExploreDatabaseName();

        File dbFile = context.getDatabasePath(exploreDatabaseName);
        File dbUpdateFile = repository.getUpdateFile();
        if (!dbFile.exists() && !dbUpdateFile.exists()) {
            repository.preloadFromAssets(dbUpdateFile);
        }

        if (dbUpdateFile.exists()) {
            log.info("found explore db update package {}", dbUpdateFile.getAbsolutePath());

            repository.deleteOldDB(dbFile);

            long dbTimestamp = repository.getTimestamp(dbUpdateFile);
            exploreDatabaseName = config.setExploreDatabaseName(dbTimestamp);
        }

        Builder<AppExploreDatabase> dbBuilder = Room.databaseBuilder(
                context,
                AppExploreDatabase.class,
                exploreDatabaseName
        );

        log.info("Build database {}", exploreDatabaseName);
        AppExploreDatabase database = buildDatabase(dbBuilder, repository, dbUpdateFile);

        if (!dbFile.exists()) {
            // execute simple query to trigger database opening (onOpenPrepackagedDatabase)
            database.query("SELECT * FROM sqlite_master", null);
        }

        return database;
    }

    @VisibleForTesting
    public static AppExploreDatabase buildDatabase(
        Builder<AppExploreDatabase> dbBuilder,
        ExploreRepository repository,
        File dbUpdateFile
    ) {
        if (dbUpdateFile.exists()) {
            log.info("create explore db from InputStream");
            dbBuilder.createFromInputStream(
                    () -> repository.getDatabaseInputStream(dbUpdateFile),
                    new PrepackagedDatabaseCallback() { });
        } else {
            log.info("create empty explore db");
        }

        RoomDatabase.Callback onOpenCallback = new RoomDatabase.Callback() {
            @Override
            public void onOpen(@NonNull SupportSQLiteDatabase db) {
                Cursor cursor = null;

                try {
                    cursor = db.query("SELECT id FROM merchant;");
                    int merchantCount = cursor.getCount();
                    cursor = db.query("SELECT id FROM atm;");
                    int atmCount = cursor.getCount();

                    if (merchantCount > 0 && atmCount > 0) {
                        repository.finalizeUpdate();
                        log.info("successfully loaded new version of explore db");
                    } else {
                        log.info("database update file was empty");
                    }
                } catch (Exception ex) {
                    log.error("error reading merchant & atm count", ex);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }

                    if (!dbUpdateFile.delete()) {
                        log.error("unable to delete " + dbUpdateFile.getAbsolutePath());
                    }
                }
            }
        };

        return dbBuilder
                .fallbackToDestructiveMigration()
                .addCallback(onOpenCallback)
                .build();
    }

    public static void forceUpdate(
        Context context,
        Configuration config,
        ExploreRepository repository
    ) {
        log.info("force update explore db");
        if (instance != null) {
            instance.close();
        }
        instance = create(context, config, repository);
    }
}
