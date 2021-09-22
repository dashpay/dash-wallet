package de.schildbach.wallet.rates;

import android.content.Context;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import org.bitcoinj.utils.Fiat;
import org.dash.wallet.common.data.CurrencyInfo;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * @author Samuel Barbosa
 */
@Entity(tableName = "exchange_rates")
public class ExchangeRate implements Parcelable {

    @PrimaryKey
    @NonNull
    private String currencyCode;
    private String rate;

    @Ignore
    private String currencyName;
    @Ignore
    private Currency currency;

    public ExchangeRate(@NonNull String currencyCode, String rate) {
        this.currencyCode = currencyCode;
        this.rate = rate;
    }

    protected ExchangeRate(Parcel in) {
        currencyCode = in.readString();
        rate = in.readString();
        currencyName = in.readString();
        currency = (Currency) in.readSerializable();
    }

    @NonNull
    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getRate() {
        return rate;
    }

    public void setRate(String rate) {
        this.rate = rate;
    }

    public Fiat getFiat() {
        final long val = new BigDecimal(rate).movePointRight(Fiat.SMALLEST_UNIT_EXPONENT).longValue();
        return Fiat.valueOf(currencyCode, val);
    }

    @Override
    public String toString() {
        return "{" + currencyCode + ":" + rate + "}";
    }

    private Currency getCurrency() {
        if (currency == null) {
            currency = Currency.getInstance(currencyCode.toUpperCase());
        }
        return currency;
    }

    public String getCurrencySymbol() {
        return getCurrency().getSymbol();
    }

    public String getCurrencyName(Context context) {

        if (currencyName == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                // currency codes must be 3 letters before calling getCurrency()
                // If the currency is not a valid ISO 4217 code, then set the
                // currency name to be equal to the currency code
                // exchanges often have "invalid" currency codes like USDT and CNH
                if(currencyCode.length() == 3) {
                    try {
                        currencyName = getCurrency().getDisplayName();
                    } catch (IllegalArgumentException x) {
                        currencyName = currencyCode;
                    }
                } else currencyName = currencyCode;

                if(currencyCode.toUpperCase().equals(currencyName.toUpperCase())) {
                    currencyName = CurrencyInfo.getOtherCurrencyName(currencyCode, context);
                }
            } else {
                // before kitkat, no names will be displayed
                // this doesn't matter since the app doesn't run on
                // pre kitkat devices
                currencyName = "";
            }
        }
        return currencyName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(currencyCode);
        dest.writeString(rate);
        dest.writeString(currencyName);
        dest.writeSerializable(currency);
    }

    public static final Creator<ExchangeRate> CREATOR = new Creator<ExchangeRate>() {
        @Override
        public ExchangeRate createFromParcel(Parcel source) {
            return new ExchangeRate(source);
        }

        @Override
        public ExchangeRate[] newArray(int size) {
            return new ExchangeRate[size];
        }
    };
}
