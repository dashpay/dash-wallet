package de.schildbach.wallet.wallofcoins.selling_wizard.add_listing;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.adapters.AddressListAdapter;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.AddressListRespVo;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by  on 06-Apr-18.
 */

public class AddressListingFragment extends SellingBaseFragment {

    private View rootView;
    private RecyclerView rv_sell_dash_ads_list;
    private ArrayList<AddressListRespVo> voArrayList;
    private ProgressBar progressBar;
    private AddressListAdapter adapter;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_selling_address_listing, container, false);
            init();
            setListeners();
            setTopbar();
            setAdapter();
            getAddressList();
            return rootView;
        } else
            return rootView;
    }

    private void setAdapter() {
        adapter = new AddressListAdapter(voArrayList);
        rv_sell_dash_ads_list.setAdapter(adapter);
    }

    private void init() {
        rv_sell_dash_ads_list = (RecyclerView) rootView.findViewById(R.id.rv_sell_dash_ads_list);
        rv_sell_dash_ads_list.setLayoutManager(new LinearLayoutManager(mContext));
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        voArrayList = new ArrayList<>();
    }

    private void setListeners() {
        //btnContinue.setOnClickListener(this);
    }

    private void setTopbar() {
        ((SellingBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_address));
    }

    private void getAddressList() {
        progressBar.setVisibility(View.VISIBLE);
        //here show the listing of ads first and if empty then redirect back to create Ads screen @getReceivingOptions
        SellingAPIClient.createService(interceptor, getActivity()).getAddressListing().
                enqueue(new Callback<ArrayList<AddressListRespVo>>() {
                    @Override
                    public void onResponse(Call<ArrayList<AddressListRespVo>> call, Response<ArrayList<AddressListRespVo>> response) {

                        if (response.code() == 200) {
                            progressBar.setVisibility(View.GONE);

                            if (response.body() != null && response.body().size() > 0) {
                                //here show the list
                                voArrayList = response.body();
                                setAdapter();

                            } else {
                                showToast(getString(R.string.no_ad_created));
                                //getReceivingOptions();
                            }
                        } else {
                            //getReceivingOptions();
                        }

                    }

                    @Override
                    public void onFailure(Call<ArrayList<AddressListRespVo>> call, Throwable t) {
                        progressBar.setVisibility(View.GONE);
                        showToast(t.getMessage());
                    }
                });
    }
}
