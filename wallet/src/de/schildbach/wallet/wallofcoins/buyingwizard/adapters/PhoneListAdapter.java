package de.schildbach.wallet.wallofcoins.buyingwizard.adapters;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.buyingwizard.models.PhoneListVO;
import de.schildbach.wallet.wallofcoins.buyingwizard.phone_list.PhoneListFragment;
import de.schildbach.wallet_test.R;


/**
 * Created by  on 19-Mar-18.
 */

public class PhoneListAdapter extends RecyclerView.Adapter<PhoneListAdapter.MyViewHolder> {

    private Context mContext;
    private ArrayList<PhoneListVO> phoneListVOS;
    private PhoneListFragment fragment;

    public PhoneListAdapter(Context context, ArrayList<PhoneListVO> phoneListVOS, PhoneListFragment fragment) {
        this.mContext = context;
        this.phoneListVOS = phoneListVOS;
        this.fragment = fragment;
    }

    @Override
    public PhoneListAdapter.MyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_buy_dash_phone_list, parent, false);

        return new MyViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(PhoneListAdapter.MyViewHolder holder, int position) {
        final PhoneListVO phoneListVO = phoneListVOS.get(holder.getAdapterPosition());

        holder.btnPhone.setText(mContext.getString(R.string.sign_in2, phoneListVO.getPhoneNumber()));

        holder.btnPhone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fragment.getAuthTokenCall(phoneListVO.getPhoneNumber(), phoneListVO.getDeviceId());
            }
        });

    }

    @Override
    public int getItemCount() {
        return phoneListVOS.size();
    }

    public class MyViewHolder extends RecyclerView.ViewHolder {
        private Button btnPhone;

        private MyViewHolder(View view) {
            super(view);
            this.btnPhone = (Button) view.findViewById(R.id.btnPhone);
        }
    }
}
