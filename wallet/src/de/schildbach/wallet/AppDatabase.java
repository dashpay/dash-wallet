package de.schildbach.wallet;

import android.arch.persistence.room.Database;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;

import de.schildbach.wallet.rates.ExchangeRate;
import de.schildbach.wallet.rates.ExchangeRatesDao;

/**
 * @author Samuel Barbosa
 */
@Database(entities = {ExchangeRate.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static AppDatabase instance;

    public abstract ExchangeRatesDao exchangeRatesDao();

    public static AppDatabase getAppDatabase() {
        if (instance == null) {
            instance = Room.databaseBuilder(WalletApplication.getInstance(),
                    AppDatabase.class, "dash-wallet-database").build();
        }
        return instance;
    }

}
