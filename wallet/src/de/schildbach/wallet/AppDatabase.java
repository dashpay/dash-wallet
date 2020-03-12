package de.schildbach.wallet;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.data.BlockchainStateDao;
import de.schildbach.wallet.data.RoomConverters;
import de.schildbach.wallet.rates.ExchangeRate;
import de.schildbach.wallet.rates.ExchangeRatesDao;

/**
 * @author Samuel Barbosa
 */
@Database(entities = {ExchangeRate.class, BlockchainState.class}, version = 2)
@TypeConverters({RoomConverters.class})
public abstract class AppDatabase extends RoomDatabase {

    private static AppDatabase instance;

    public abstract ExchangeRatesDao exchangeRatesDao();
    public abstract BlockchainStateDao blockchainStateDao();

    public static AppDatabase getAppDatabase() {
        if (instance == null) {
            instance = Room.databaseBuilder(WalletApplication.getInstance(), AppDatabase.class,
                    "dash-wallet-database").fallbackToDestructiveMigration().build();
        }
        return instance;
    }

}
