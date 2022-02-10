package de.schildbach.wallet;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.sqlite.db.SupportSQLiteDatabase;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import org.dash.wallet.features.exploredash.data.AtmDao;
import org.dash.wallet.features.exploredash.data.MerchantDao;
import org.dash.wallet.features.exploredash.data.model.Atm;
import org.dash.wallet.features.exploredash.data.model.AtmFTS;
import org.dash.wallet.features.exploredash.data.model.Merchant;
import org.dash.wallet.features.exploredash.data.model.MerchantFTS;
import org.dash.wallet.features.exploredash.repository.GCExploreDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;

import de.schildbach.wallet.data.RoomConverters;

/**
 * @author Samuel Barbosa
 */
@Database(entities = {
        Merchant.class,
        MerchantFTS.class,
        Atm.class,
        AtmFTS.class
}, version = 1)
@TypeConverters({RoomConverters.class})
public abstract class AppExploreDatabase extends RoomDatabase {

    private static final Logger log = LoggerFactory.getLogger(AppExploreDatabase.class);

    private final static String DB_FILE_NAME = "explore-database";
    private final static String DB_ASSET_FILE_NAME = "explore/" + GCExploreDatabase.DATA_FILE_NAME;

    private static AppExploreDatabase instance;

    private static final WalletApplication walletApp = WalletApplication.getInstance();

    public abstract MerchantDao merchantDao();

    public abstract AtmDao atmDao();

    public static AppExploreDatabase getAppDatabase() {
        if (instance == null) {
            instance = create();
        }
        return instance;
    }

    private static AppExploreDatabase create() {
        Builder<AppExploreDatabase> dbBuilder = Room.databaseBuilder(walletApp, AppExploreDatabase.class, DB_FILE_NAME);
        try {
            File dbFile = walletApp.getDatabasePath(DB_FILE_NAME);
            File dbZipFile = new File(walletApp.getCacheDir(), GCExploreDatabase.DATA_FILE_NAME);
            if (!dbFile.exists() && !dbZipFile.exists()) {
                preloadFromAssets(walletApp, dbZipFile);
            }
            if (dbZipFile.exists()) {
                log.info("found explore db update package {}", dbZipFile.getAbsolutePath());
                dbBuilder.createFromInputStream(
                        new Callable<InputStream>() {
                            @Override
                            public InputStream call() throws Exception {
                                return getDatabaseInputStream(dbZipFile);
                            }
                        },
                        new PrepackagedDatabaseCallback() {
                            @Override
                            public void onOpenPrepackagedDatabase(@NonNull SupportSQLiteDatabase db) {
                                if (!dbZipFile.delete()) {
                                    log.error("unable to delete " + dbZipFile.getAbsolutePath());
                                }
                                log.info("successfully loaded new version of explode db");
                            }
                        });
            }

        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        return dbBuilder.fallbackToDestructiveMigration().build();
    }

    private static void preloadFromAssets(WalletApplication walletApp, File dbZipFile) throws IOException {
        log.info("preloading explore db from assets {}", dbZipFile.getAbsolutePath());
        try (InputStream in = walletApp.getAssets().open(DB_ASSET_FILE_NAME);
             FileOutputStream out = new FileOutputStream(dbZipFile)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (FileNotFoundException ex) {
            log.warn("missing {}, explore db will be empty", DB_ASSET_FILE_NAME);
        }
    }

    private static InputStream getDatabaseInputStream(File file) throws IOException {
        ZipFile zipFile = new ZipFile(file);
        String[] comment = zipFile.getComment().split("#");
        String timestamp = comment[0];
        String checksum = comment[1];
        log.info("package timestamp {}, checksum {}", timestamp, checksum);
        zipFile.setPassword(checksum.toCharArray());
        FileHeader zipHeader = zipFile.getFileHeader("explore.db");
        return zipFile.getInputStream(zipHeader);
    }

    public static void forceUpdate() {
        log.info("force update explore db");
        if (instance != null) {
            instance.close();
        }
        try {
            File dbFile = walletApp.getApplicationContext().getDatabasePath(DB_FILE_NAME);
            boolean dbDelete = dbFile.delete();
            boolean dbShmDelete = new File(dbFile.getAbsolutePath() + "-shm").delete();
            boolean dbWalDelete = new File(dbFile.getAbsolutePath() + "-wal").delete();
            log.info("delete existing explore db ({}, {}, {})", dbDelete, dbShmDelete, dbWalDelete);
        } catch (SecurityException ex) {
            log.warn("unable to delete explore db", ex);
        }
        instance = create();
        // execute simple query to trigger database opening
        instance.query("SELECT * FROM sqlite_master", null);
    }
}
