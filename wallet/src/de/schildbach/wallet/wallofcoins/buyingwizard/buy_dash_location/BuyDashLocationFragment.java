package de.schildbach.wallet.wallofcoins.buyingwizard.buy_dash_location;

import android.content.Context;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseActivity;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.zip.BuyDashZipFragment;
import de.schildbach.wallet_test.R;
import de.schildbach.wallet_test.databinding.LayoutBuyDashLocationBinding;

/**
 * Created on 6/3/18.
 */

public class BuyDashLocationFragment extends BuyDashBaseFragment {

    LayoutBuyDashLocationBinding binding;


    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.layout_buy_dash_location, container, false);

        init();
        setListeners();
        return binding.getRoot();
    }

    private void init() {

    }

    private void setListeners() {
        binding.buttonBuyDashGetLocation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (checkPermissions())
                    Log.e("Permission added", "added");
                else
                    requestLocationPermission();
            }
        });
        binding.buttonBuyDashGetLocationNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                ((BuyDashBaseActivity) mContext).replaceFragment(new BuyDashZipFragment(), true, true, "BuyDashZipFragment");
            }
        });
    }
    
}
