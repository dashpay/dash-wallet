package de.schildbach.wallet.rates;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import org.dash.wallet.common.data.ExchangeRate;

import java.util.List;

import kotlinx.coroutines.flow.Flow;

/**
 * @author Samuel Barbosa
 */
@Dao
public interface ExchangeRatesDao {

    @Query("SELECT * FROM exchange_rates ORDER BY currencyCode")
    LiveData<List<ExchangeRate>> getAll();

    @Query("SELECT * FROM exchange_rates ORDER BY currencyCode")
    Flow<List<ExchangeRate>> observeAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ExchangeRate> exchangeRates);

    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :currencyCode LIMIT 1")
    LiveData<ExchangeRate> getRate(String currencyCode);

    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :currencyCode LIMIT 1")
    Flow<ExchangeRate> observeRate(@NonNull String currencyCode);

    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :currencyCode LIMIT 1")
    ExchangeRate getRateSync(String currencyCode);

    @Query("SELECT * FROM exchange_rates WHERE currencyCode LIKE :currencyCode || '%' ORDER BY currencyCode")
    LiveData<List<ExchangeRate>> searchRates(String currencyCode);

    @Query("SELECT count(*) FROM exchange_rates")
    int count();

    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :currencyCode LIMIT 1")
    ExchangeRate getExchangeRateForCurrency(String currencyCode);
}
