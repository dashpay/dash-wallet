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

import de.schildbach.wallet.wallofcoins.selling_wizard.SellingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.adapters.SellingWizardAddressListAdapter;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardAddressListRespVo;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 06-Apr-18.
 */

public class SellingWizardAddressListingFragment extends SellingWizardBaseFragment {

    private View rootView;
    private RecyclerView recycler_sell_coin;
    private ArrayList<SellingWizardAddressListRespVo> voArrayList;
    private ProgressBar progressBar;
    private SellingWizardAddressListAdapter adapter;

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
        adapter = new SellingWizardAddressListAdapter(voArrayList);
        recycler_sell_coin.setAdapter(adapter);
    }

    private void init() {
        recycler_sell_coin = (RecyclerView) rootView.findViewById(R.id.recycler_sell_coin);
        recycler_sell_coin.setLayoutManager(new LinearLayoutManager(mContext));
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        voArrayList = new ArrayList<>();
    }

    private void setListeners() {
        //btnContinue.setOnClickListener(this);
    }

    private void setTopbar() {
        ((SellingWizardBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_address));
    }

    private void getAddressList() {
        progressBar.setVisibility(View.VISIBLE);
        //here show the listing of ads first and if empty then redirect back to create Ads screen @getReceivingOptions
        SellingAPIClient.createService(interceptor, getActivity()).getAddressListing().
                enqueue(new Callback<ArrayList<SellingWizardAddressListRespVo>>() {
                    @Override
                    public void onResponse(Call<ArrayList<SellingWizardAddressListRespVo>> call,
                                           Response<ArrayList<SellingWizardAddressListRespVo>> response) {

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
                    public void onFailure(Call<ArrayList<SellingWizardAddressListRespVo>> call, Throwable t) {
                        progressBar.setVisibility(View.GONE);
                        showToast(t.getMessage());
                    }
                });
    }
}
