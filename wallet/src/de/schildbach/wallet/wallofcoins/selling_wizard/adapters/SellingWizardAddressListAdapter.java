package de.schildbach.wallet.wallofcoins.selling_wizard.adapters;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardAddressListRespVo;
import de.schildbach.wallet_test.R;

/**
 * Created on 06-Apr-18.
 */

public class SellingWizardAddressListAdapter extends RecyclerView.Adapter<SellingWizardAddressListAdapter.VHListing> {


    private ArrayList<SellingWizardAddressListRespVo> body;
    LayoutInflater inflater;

    public SellingWizardAddressListAdapter(ArrayList<SellingWizardAddressListRespVo> body) {
        this.body = body;
    }

    @Override
    public VHListing onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_selling_address, parent, false);

        return new VHListing(itemView);
    }

    @Override
    public void onBindViewHolder(VHListing holder, int position) {
        holder.text_current_price.setText(body.get(position).getCurrentPrice());
    }

    @Override
    public int getItemCount() {
        return body.size();
    }

    class VHListing extends RecyclerView.ViewHolder {
        private TextView text_current_price;

        private VHListing(View view) {
            super(view);
            this.text_current_price = (TextView) view.findViewById(R.id.text_current_price);
        }
    }
}
