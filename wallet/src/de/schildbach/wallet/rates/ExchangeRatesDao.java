package de.schildbach.wallet.rates;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * @author Samuel Barbosa
 */
@Dao
public interface ExchangeRatesDao {

    @Query("SELECT * FROM exchange_rates ORDER BY currencyCode")
    LiveData<List<ExchangeRate>> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ExchangeRate> exchangeRates);

    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :currencyCode LIMIT 1")
    LiveData<ExchangeRate> getRate(String currencyCode);

    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :currencyCode LIMIT 1")
    ExchangeRate getRateSync(String currencyCode);

    @Query("SELECT * FROM exchange_rates WHERE currencyCode LIKE :currencyCode || '%' ORDER BY currencyCode")
    LiveData<List<ExchangeRate>> searchRates(String currencyCode);

    @Query("SELECT count(*) FROM exchange_rates")
    int count();

}
