package de.schildbach.wallet.wallofcoins.selling_wizard.adapters;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardPhoneListVO;
import de.schildbach.wallet.wallofcoins.selling_wizard.phone_list.SellingWizardPhoneListFragment;
import de.schildbach.wallet_test.R;


/**
 * Created on 19-Mar-18.
 */

public class SellingWizardPhoneListAdapter extends RecyclerView.Adapter<SellingWizardPhoneListAdapter.MyViewHolder> {

    private Context mContext;
    private ArrayList<SellingWizardPhoneListVO> sellingWizardPhoneListVOS;
    private SellingWizardPhoneListFragment fragment;

    public SellingWizardPhoneListAdapter(Context context, ArrayList<SellingWizardPhoneListVO> sellingWizardPhoneListVOS,
                                         SellingWizardPhoneListFragment fragment) {
        this.mContext = context;
        this.sellingWizardPhoneListVOS = sellingWizardPhoneListVOS;
        this.fragment = fragment;
    }

    @Override
    public SellingWizardPhoneListAdapter.MyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_selling_phone_list, parent, false);

        return new MyViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(SellingWizardPhoneListAdapter.MyViewHolder holder, final int position) {
        final SellingWizardPhoneListVO sellingWizardPhoneListVO = sellingWizardPhoneListVOS.get(holder.getAdapterPosition());

        holder.button_phone.setText(mContext.getString(R.string.sign_in2, sellingWizardPhoneListVO.getPhoneNumber()));

        holder.button_phone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fragment.onItemClick(sellingWizardPhoneListVO.getPhoneNumber());
            }
        });

    }

    @Override
    public int getItemCount() {
        return sellingWizardPhoneListVOS.size();
    }

    public class MyViewHolder extends RecyclerView.ViewHolder {
        private Button button_phone;

        private MyViewHolder(View view) {
            super(view);
            this.button_phone = (Button) view.findViewById(R.id.button_phone);
        }
    }
}
