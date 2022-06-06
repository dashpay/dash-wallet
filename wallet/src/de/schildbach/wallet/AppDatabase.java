package de.schildbach.wallet;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import org.dash.wallet.common.data.ExchangeRate;
import org.dash.wallet.common.transactions.TransactionMetadata;

import de.schildbach.wallet.data.AppDatabaseMigrations;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.data.BlockchainStateDao;
import de.schildbach.wallet.data.RoomConverters;
import de.schildbach.wallet.data.TransactionMetadataDao;
import de.schildbach.wallet.rates.ExchangeRatesDao;

/**
 * @author Samuel Barbosa
 */
@Database(entities = {
        ExchangeRate.class,
        BlockchainState.class,
        TransactionMetadata.class
    }, version = 11)
@TypeConverters({RoomConverters.class})
public abstract class AppDatabase extends RoomDatabase {

    private static AppDatabase instance;

    public abstract ExchangeRatesDao exchangeRatesDao();
    public abstract BlockchainStateDao blockchainStateDao();
    public abstract TransactionMetadataDao transactionMetadataDao();

    public static AppDatabase getAppDatabase() {
        if (instance == null) {
            instance = Room.databaseBuilder(WalletApplication.getInstance(),
                    AppDatabase.class, "dash-wallet-database")
                    .addMigrations(
                            AppDatabaseMigrations.getMigration8To10(),
                            AppDatabaseMigrations.getMigration9To10()
                    )
                    .fallbackToDestructiveMigration().build();
        }
        return instance;
    }

}
