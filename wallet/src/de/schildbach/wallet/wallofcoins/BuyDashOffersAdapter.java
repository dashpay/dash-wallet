package de.schildbach.wallet.wallofcoins;


import android.content.Context;
import android.databinding.DataBindingUtil;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import java.util.List;

import de.schildbach.wallet.response.GetOffersResp;
import hashengineering.darkcoin.wallet.R;
import hashengineering.darkcoin.wallet.databinding.BuyDashOffersItemBinding;

class BuyDashOffersAdapter extends RecyclerView.Adapter<BuyDashOffersAdapter.VHolder> {

    private Context context;
    private List<GetOffersResp.SingleDepositBean> singleDepositBeenList;
    private AdapterView.OnItemSelectedListener onItemSelectedListener;

    public BuyDashOffersAdapter(Context context, List<GetOffersResp.SingleDepositBean> singleDepositBeenList, AdapterView.OnItemSelectedListener onItemSelectedListener) {
        this.context = context;
        this.singleDepositBeenList = singleDepositBeenList;
        this.onItemSelectedListener = onItemSelectedListener;
    }

    @Override
    public VHolder onCreateViewHolder(ViewGroup group, int i) {
        LayoutInflater layoutInflater = LayoutInflater.from(context);
        BuyDashOffersItemBinding binding = DataBindingUtil.inflate(layoutInflater, R.layout.buy_dash_offers_item, group, false);
        return new VHolder(binding);
    }

    @Override
    public void onBindViewHolder(final VHolder holder, final int i) {
        final GetOffersResp.SingleDepositBean bean = singleDepositBeenList.get(i);

        holder.binding.setItem(bean);

        holder.binding.buttonBuyDashItemOrder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onItemSelectedListener.onItemSelected(null, holder.binding.getRoot(), i, 0);
            }
        });
    }

    @Override
    public int getItemCount() {
        return singleDepositBeenList.size();
    }

    public class VHolder extends RecyclerView.ViewHolder {
        private BuyDashOffersItemBinding binding;

        public VHolder(BuyDashOffersItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
