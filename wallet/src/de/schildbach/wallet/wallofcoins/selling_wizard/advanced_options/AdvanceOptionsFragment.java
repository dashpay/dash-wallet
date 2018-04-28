package de.schildbach.wallet.wallofcoins.selling_wizard.advanced_options;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.AddressVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.verify_details.VerifySellingDetailsFragment;
import de.schildbach.wallet_test.R;

/**
 * Created by  on 20-Apr-18.
 */

public class AdvanceOptionsFragment extends SellingBaseFragment implements View.OnClickListener {

    private View rootView;
    private CheckBox checkbox;
    private Button button_cancle, button_save;
    private EditText edit_min_payment, edit_max_payment;
    private AddressVo addressVo;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.dialog_selling_options, container, false);
            init();
            setListeners();
            setTopbar();
            handleArgs();
            return rootView;
        } else
            return rootView;
    }

    private void init() {

        edit_min_payment = (EditText) rootView.findViewById(R.id.edit_min_payment);
        edit_max_payment = (EditText) rootView.findViewById(R.id.edit_max_payment);
        button_cancle = (Button) rootView.findViewById(R.id.button_cancle);
        button_save = (Button) rootView.findViewById(R.id.button_save);
        checkbox = (CheckBox) rootView.findViewById(R.id.checkbox);

        button_save.setText(getString(R.string.action_continue));
    }

    private void setListeners() {
        button_cancle.setOnClickListener(this);
        button_save.setOnClickListener(this);
    }

    private void setTopbar() {
        ((SellingBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_advanced_options));
    }

    private void handleArgs() {

        if (getArguments() != null) {
            addressVo = (AddressVo)
                    getArguments().getSerializable(SellingConstants.ARGUMENT_ADDRESS_DETAILS_VO);
        }
    }

    @Override
    public void onClick(View view) {

        switch (view.getId()) {
            case R.id.button_save:
                if (isValidDetails()) {
                    Bundle bundle = new Bundle();
                    bundle.putSerializable(SellingConstants.ARGUMENT_ADDRESS_DETAILS_VO, getSellingDetails());
                    VerifySellingDetailsFragment fragment = new VerifySellingDetailsFragment();
                    fragment.setArguments(bundle);

                    ((SellingBaseActivity) mContext).replaceFragment(fragment, true, true);
                }
                break;
            case R.id.button_cancle:
                showToast("Under Implementation");
                break;


        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setTopbar();
    }

    private boolean isValidDetails() {

        if (edit_min_payment.getText().toString().isEmpty()) {
            showToast(getString(R.string.enter_min_payment));
            edit_min_payment.requestFocus();
            return false;
        } else if (edit_max_payment.getText().toString().isEmpty()) {
            showToast(getString(R.string.enter_max_payment));
            edit_max_payment.requestFocus();
            return false;
        } else if (!checkbox.isChecked()) {
            return false;
        }
        return true;
    }

    private AddressVo getSellingDetails() {

        return addressVo;
    }
}