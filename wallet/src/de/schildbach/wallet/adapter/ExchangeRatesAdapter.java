package de.schildbach.wallet.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import org.bitcoinj.core.Coin;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.data.entity.ExchangeRate;
import org.dash.wallet.common.ui.CurrencyTextView;

import java.util.List;
import java.util.Locale;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet_test.R;

public class ExchangeRatesAdapter extends BaseFilterAdapter<ExchangeRate, ExchangeRatesAdapter.ExchangeRateViewHolder> {

    private Context mAppContext;
    private String defaultCurrency = null;
    private Coin rateBase = Coin.COIN;
    private Configuration mAppConfig;
    private Wallet mWallet;
    private onExchangeRateItemSelectedListener itemSelectedListener;
    private boolean isShownAsDialog;

    public ExchangeRatesAdapter(Context mAppContext, Configuration configuration,
                                Wallet wallet, List<ExchangeRate> exchangeRateList,
                                ResetViewListener listener,
                                onExchangeRateItemSelectedListener itemSelectedListener,
                                boolean isShownAsDialog
    ) {
        super(listener);
        this.mAppContext = mAppContext;
        mOriginalList = exchangeRateList;
        mFilteredList = exchangeRateList;
        this.mAppConfig = configuration;
        this.mWallet = wallet;
        this.itemSelectedListener = itemSelectedListener;
        this.isShownAsDialog = isShownAsDialog;
    }

    @Override
    protected void filterObject(List<ExchangeRate> filteredList, ExchangeRate object, CharSequence searchText) {
        if (object.getCurrencyName(mAppContext).toLowerCase(Locale.ROOT).contains(searchText) || object.getCurrencyCode().toLowerCase(Locale.ROOT).contains(searchText))
            filteredList.add(object);
    }

    @NonNull
    @Override
    public ExchangeRateViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ExchangeRateViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.exchange_rate_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ExchangeRateViewHolder holder, int position) {
        final ExchangeRate exchangeRate = getListItem(position);
        org.bitcoinj.utils.ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(Coin.COIN, exchangeRate.getFiat());

        final boolean isDefaultCurrency = exchangeRate.getCurrencyCode().equals(defaultCurrency);

        holder.defaultCurrencyCheckbox.setOnCheckedChangeListener(null);
        holder.defaultCurrencyCheckbox.setChecked(isDefaultCurrency);
        holder.currencyCode.setText(exchangeRate.getCurrencyCode());
        holder.currencyName.setText(exchangeRate.getCurrencyName(mAppContext));
        holder.price.setFormat(!rateBase.isLessThan(Coin.COIN) ? Constants.LOCAL_FORMAT.minDecimals(2)
                : Constants.LOCAL_FORMAT.minDecimals(4));
        holder.price.setAmount(rate.coinToFiat(rateBase));
        holder.defaultCurrencyCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    holder.defaultCurrencyCheckbox.setChecked(true);
                    setDefaultCurrency(exchangeRate.getCurrencyCode());
                    WalletBalanceWidgetProvider.updateWidgets(mAppContext, mWallet);
                    if (!isShownAsDialog) {
                        mAppConfig.setExchangeCurrencyCode(exchangeRate.getCurrencyCode());
                    }
                    itemSelectedListener.onItemChecked(exchangeRate);
                } else if (isDefaultCurrency){
                    holder.defaultCurrencyCheckbox.setChecked(true);
                    itemSelectedListener.onItemChecked(null);
                }
            }
        });

        Glide.with(mAppContext.getApplicationContext()).load(getFlagFromCurrencyCode(exchangeRate.getCurrencyCode())).apply(RequestOptions.circleCropTransform()).into(holder.currencyFlag);
        holder.itemSeparator.setVisibility(position == getItemCount() - 1 ? View.GONE : View.VISIBLE);
    }

    private Integer getFlagFromCurrencyCode(String currencyCode) {
        final int resourceId = mAppContext.getApplicationContext().getResources().getIdentifier("currency_code_" + currencyCode.toLowerCase(Locale.ROOT), "drawable", mAppContext.getPackageName());
        return resourceId == 0 ? R.drawable.ic_default_flag : resourceId;
    }

    public void setDefaultCurrency(final String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
        notifyDataSetChanged();
    }

    public void setRateBase(final Coin rateBase) {
        this.rateBase = rateBase;
        notifyDataSetChanged();
    }

    public int getDefaultCurrencyPosition() {
        if (getFilteredList() == null || defaultCurrency == null) {
            return RecyclerView.NO_POSITION;
        }

        int i = 0;
        for (ExchangeRate rate : getFilteredList()) {
            if (rate.getCurrencyCode().equalsIgnoreCase(defaultCurrency)) {
                return i;
            }
            i++;
        }

        return RecyclerView.NO_POSITION;
    }


    protected final class ExchangeRateViewHolder extends RecyclerView.ViewHolder {
        private final TextView currencyCode;
        private final TextView currencyName;
        private final CurrencyTextView price;
        private final CheckBox defaultCurrencyCheckbox;
        private final ImageView currencyFlag;
        private final View itemSeparator;

        ExchangeRateViewHolder(final View itemView) {
            super(itemView);
            currencyCode = itemView.findViewById(R.id.local_currency_code);
            currencyName = itemView.findViewById(R.id.local_currency_name);
            price = itemView.findViewById(R.id.price);
            defaultCurrencyCheckbox = itemView.findViewById(R.id.checkbox);
            currencyFlag = itemView.findViewById(R.id.local_currency_flag);
            itemSeparator = itemView.findViewById(R.id.item_divider);
        }

    }

    public interface onExchangeRateItemSelectedListener {
        void onItemChecked(ExchangeRate selectedRate);
    }
}
