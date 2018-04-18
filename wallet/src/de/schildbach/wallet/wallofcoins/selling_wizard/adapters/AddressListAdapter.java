package de.schildbach.wallet.wallofcoins.selling_wizard.adapters;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.selling_wizard.models.AddressListRespVo;
import de.schildbach.wallet_test.R;


/**
 * Created by  on 06-Apr-18.
 */

public class AddressListAdapter extends RecyclerView.Adapter<AddressListAdapter.VHListing> {


    private ArrayList<AddressListRespVo> body;
    LayoutInflater inflater;

    public AddressListAdapter(ArrayList<AddressListRespVo> body) {
        this.body = body;
    }

    @Override
    public VHListing onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.layout_selling_address_adapter_items, parent, false);

        return new VHListing(itemView);
    }

    @Override
    public void onBindViewHolder(VHListing holder, int position) {
        holder.txtViewCurrentPrice.setText(body.get(position).getCurrentPrice());
    }

    @Override
    public int getItemCount() {
        return body.size();
    }

    class VHListing extends RecyclerView.ViewHolder {
        private TextView txtViewCurrentPrice;

        private VHListing(View view) {
            super(view);
            this.txtViewCurrentPrice = (TextView) view.findViewById(R.id.txtViewCurrentPrice);
        }
    }
}
