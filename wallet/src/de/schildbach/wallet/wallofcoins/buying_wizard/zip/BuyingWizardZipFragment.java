package de.schildbach.wallet.wallofcoins.buying_wizard.zip;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;

import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.offer_amount.BuyingWizardOfferAmountFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.payment_center.BuyingWizardPaymentCenterFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardFragmentUtils;
import de.schildbach.wallet_test.R;


/**
 * Created on 6/3/18.
 */

public class BuyingWizardZipFragment extends BuyingWizardBaseFragment implements View.OnClickListener {

    private View rootView;
    private Button button_zip_next;
    private String zipCode;
    private EditText edit_zip;
    private final String TAG = "BuyingWizardZipFragment";

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_buying_zip, container, false);
            init();
            setListeners();
            return rootView;
        } else
            return rootView;
    }

    private void init() {

        button_zip_next = (Button) rootView.findViewById(R.id.button_zip_next);
        edit_zip = (EditText) rootView.findViewById(R.id.edit_zip);
    }

    private void setListeners() {
        button_zip_next.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.button_zip_next:
                zipCode = edit_zip.getText().toString().trim();
                if (TextUtils.isEmpty(zipCode)) { // open bank list screen
                    navigateToBankListScreen();
                } else {
                    if (isValidZip())
                        navigateToOtherScreen1();
                }
                break;
        }
    }

    private boolean isValidZip() {
        if (edit_zip.getText().toString().trim().length() < 5 || edit_zip.getText().toString().trim().length() > 6) {
            edit_zip.requestFocus();
            showToast(getString(R.string.invalid_zip_code));
            return false;
        }
        return true;
    }

    private void navigateToOtherScreen1() {
        Bundle bundle = new Bundle();
        bundle.putString(WOCConstants.ARG_ZIP, zipCode);
        BuyingWizardOfferAmountFragment offerAmountFragment = new BuyingWizardOfferAmountFragment();
        offerAmountFragment.setArguments(bundle);

        ((BuyingWizardBaseActivity) mContext).replaceFragment(offerAmountFragment, true, true);
    }

    //if zip code is empty user navigate to all bank list screen
    private void navigateToBankListScreen() {
        BuyingWizardPaymentCenterFragment centerFragment = new BuyingWizardPaymentCenterFragment();
        ((BuyingWizardBaseActivity) mContext).replaceFragment(centerFragment, true, true);
    }

    //this method remove animation when user want to clear back stack
    @Override
    public Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        if (BuyingWizardFragmentUtils.sDisableFragmentAnimations) {
            Animation a = new Animation() {
            };
            a.setDuration(0);
            return a;
        }
        return super.onCreateAnimation(transit, enter, nextAnim);
    }
}
