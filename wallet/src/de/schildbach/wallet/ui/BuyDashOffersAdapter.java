package de.schildbach.wallet.ui;


import android.content.Context;
import android.databinding.DataBindingUtil;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import java.util.List;

import de.schildbach.wallet.response.GetOffersResp;
import hashengineering.darkcoin.wallet.R;
import hashengineering.darkcoin.wallet.databinding.BuyDashOffersItemBinding;

class BuyDashOffersAdapter extends RecyclerView.Adapter<BuyDashOffersAdapter.VHolder> {

    private Context context;
    private List<GetOffersResp.SingleDepositBean> singleDepositBeenList;

    public BuyDashOffersAdapter(Context context, List<GetOffersResp.SingleDepositBean> singleDepositBeenList) {
        this.context = context;
        this.singleDepositBeenList = singleDepositBeenList;
    }

    @Override
    public VHolder onCreateViewHolder(ViewGroup group, int i) {
        LayoutInflater layoutInflater = LayoutInflater.from(context);
        BuyDashOffersItemBinding binding = DataBindingUtil.inflate(layoutInflater, R.layout.buy_dash_offers_item, group, false);
        return new VHolder(binding);
    }

    @Override
    public void onBindViewHolder(VHolder holder, int i) {
        final GetOffersResp.SingleDepositBean bean = singleDepositBeenList.get(i);

        holder.binding.setItem(bean);

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
