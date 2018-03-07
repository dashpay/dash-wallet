package de.schildbach.wallet.wallofcoins.buyingwizard.zip;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import java.util.List;

import de.schildbach.wallet.wallofcoins.BuyDashPref;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseActivity;
import de.schildbach.wallet.wallofcoins.buyingwizard.BuyDashBaseFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.offer_amount.BuyDashOfferAmountFragment;
import de.schildbach.wallet.wallofcoins.buyingwizard.payment_center.BuyDashPaymentCenterFragment;
import de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp;
import de.schildbach.wallet_test.R;

/**
 * Created on 6/3/18.
 */

public class BuyDashZipFragment extends BuyDashBaseFragment implements View.OnClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private View rootView;
    private Button button_buy_dash_zip_next;
    private ImageView imgViewToolbarBack;
    private String zipCode;
    private EditText buy_dash_zip;
    private final String TAG = "BuyDashZipFragment";
    private BuyDashPref buyDashPref;
    private List<GetReceivingOptionsResp> receivingOptionsResps;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.layout_buy_dash_zip, container, false);
            init();
            setListeners();
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(mContext));

        button_buy_dash_zip_next = (Button) rootView.findViewById(R.id.button_buy_dash_zip_next);
        imgViewToolbarBack = (ImageView) rootView.findViewById(R.id.imgViewToolbarBack);
        buy_dash_zip = (EditText) rootView.findViewById(R.id.buy_dash_zip);
    }

    private void setListeners() {
        button_buy_dash_zip_next.setOnClickListener(this);
        imgViewToolbarBack.setOnClickListener(this);
        buyDashPref.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.imgViewToolbarBack:
                ((BuyDashBaseActivity) mContext).popbackFragment();
                break;
            case R.id.button_buy_dash_zip_next:
                zipCode = buy_dash_zip.getText().toString().trim();
                if (TextUtils.isEmpty(zipCode)) {
                    navigateToOtherScreen();
                } else {
                    navigateToOtherScreen1();
                }
                break;


        }
    }

    private void navigateToOtherScreen1() {
        Bundle bundle = new Bundle();
        bundle.putString(WOCConstants.ZIP, zipCode);
        BuyDashOfferAmountFragment offerAmountFragment = new BuyDashOfferAmountFragment();
        offerAmountFragment.setArguments(bundle);

        ((BuyDashBaseActivity) mContext).replaceFragment(offerAmountFragment, true, true,
                "BuyDashOfferAmountFragment");
    }

    //if zip code is empty user navigate to all bank list screen
    private void navigateToOtherScreen() {
        BuyDashPaymentCenterFragment centerFragment = new BuyDashPaymentCenterFragment();
        ((BuyDashBaseActivity) mContext).replaceFragment(centerFragment, true, true,
                "BuyDashPaymentCenterFragment");
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {

    }
}
