package de.schildbach.wallet;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.sqlite.db.SupportSQLiteDatabase;

import org.dash.wallet.common.Configuration;
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

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;
import de.schildbach.wallet.data.RoomConverters;

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

    @EntryPoint
    @InstallIn(SingletonComponent.class)
    interface AppExploreDatabaseEntryPoint {
        ExploreRepository getExploreRepository();
    }

    public static AppExploreDatabase getAppDatabase() {
        if (instance == null) {
            instance = create();
        }
        return instance;
    }

    private static AppExploreDatabase create() {
        WalletApplication walletApp = WalletApplication.getInstance();
        Configuration appConfiguration = walletApp.getConfiguration();
        String exploreDatabaseName = appConfiguration.getExploreDatabaseName();

        AppExploreDatabaseEntryPoint entryPoint = EntryPointAccessors.fromApplication(walletApp, AppExploreDatabaseEntryPoint.class);
        ExploreRepository exploreRepository = entryPoint.getExploreRepository();

        File dbFile = walletApp.getDatabasePath(exploreDatabaseName);
        File dbUpdateFile = exploreRepository.getUpdateFile();
        if (!dbFile.exists() && !dbUpdateFile.exists()) {
            exploreRepository.preloadFromAssets(dbUpdateFile);
        }

        Builder<AppExploreDatabase> dbBuilder;
        if (dbUpdateFile.exists()) {
            log.info("found explore db update package {}", dbUpdateFile.getAbsolutePath());

            exploreRepository.deleteOldDB(dbFile);

            long dbTimestamp = exploreRepository.getTimestamp(dbUpdateFile);
            exploreDatabaseName = appConfiguration.setExploreDatabaseName(dbTimestamp);
        }

        dbBuilder = Room.databaseBuilder(walletApp, AppExploreDatabase.class, exploreDatabaseName);

        if (dbUpdateFile.exists()) {
            log.info("create explore db from InputStream {}", exploreDatabaseName);
            dbBuilder.createFromInputStream(
                    () -> exploreRepository.getDatabaseInputStream(dbUpdateFile),
                    new PrepackagedDatabaseCallback() {
                        @Override
                        public void onOpenPrepackagedDatabase(@NonNull SupportSQLiteDatabase db) {
                            exploreRepository.finalizeUpdate(dbUpdateFile);
                        }
                    });
        } else {
            log.info("create empty explore db");
        }

        AppExploreDatabase database = dbBuilder.fallbackToDestructiveMigration().build();
        if (!dbFile.exists()) {
            // execute simple query to trigger database opening (onOpenPrepackagedDatabase)
            database.query("SELECT * FROM sqlite_master", null);
        }
        return database;
    }

    public static void forceUpdate() {
        log.info("force update explore db");
        if (instance != null) {
            instance.close();
        }
        instance = create();
    }
}
