package de.schildbach.wallet.wallofcoins.buying_wizard.adapters;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.buying_wizard.models.BuyingWizardPhoneListVO;
import de.schildbach.wallet.wallofcoins.buying_wizard.phone_list.BuyingWizardPhoneListFragment;
import de.schildbach.wallet_test.R;


/**
 * Created on 19-Mar-18.
 */

public class BuyingWizardPhoneListAdapter extends RecyclerView.Adapter<BuyingWizardPhoneListAdapter.MyViewHolder> {

    private Context mContext;
    private ArrayList<BuyingWizardPhoneListVO> buyingWizardPhoneListVOS;
    private BuyingWizardPhoneListFragment fragment;

    public BuyingWizardPhoneListAdapter(Context context, ArrayList<BuyingWizardPhoneListVO> buyingWizardPhoneListVOS, BuyingWizardPhoneListFragment fragment) {
        this.mContext = context;
        this.buyingWizardPhoneListVOS = buyingWizardPhoneListVOS;
        this.fragment = fragment;
    }

    @Override
    public BuyingWizardPhoneListAdapter.MyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_buying_phone_list, parent, false);

        return new MyViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(BuyingWizardPhoneListAdapter.MyViewHolder holder, int position) {
        final BuyingWizardPhoneListVO buyingWizardPhoneListVO = buyingWizardPhoneListVOS.get(holder.getAdapterPosition());

        holder.btnPhone.setText(mContext.getString(R.string.sign_in2, buyingWizardPhoneListVO.getPhoneNumber()));

        holder.btnPhone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fragment.getAuthTokenCall(buyingWizardPhoneListVO.getPhoneNumber(), buyingWizardPhoneListVO.getDeviceId());
            }
        });

    }

    @Override
    public int getItemCount() {
        return buyingWizardPhoneListVOS.size();
    }

    public class MyViewHolder extends RecyclerView.ViewHolder {
        private Button btnPhone;

        private MyViewHolder(View view) {
            super(view);
            this.btnPhone = (Button) view.findViewById(R.id.btnPhone);
        }
    }
}
