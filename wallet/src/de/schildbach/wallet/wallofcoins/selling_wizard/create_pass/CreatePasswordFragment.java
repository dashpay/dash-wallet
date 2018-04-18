package de.schildbach.wallet.wallofcoins.selling_wizard.create_pass;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseFragment;
import de.schildbach.wallet_test.R;


/**
 * Created by  on 06-Apr-18.
 */

public class CreatePasswordFragment extends SellingBaseFragment {
    private View rootView;
    private ProgressBar progressBar;
    private EditText edtViewMobile, edtViewNewPass, edtViewConfirmNewPass;
    private Button btnSetPass;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.layout_selling_create_pass, container, false);
            init();
            setTopbar();
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        edtViewMobile = (EditText) rootView.findViewById(R.id.edtViewMobile);
        edtViewNewPass = (EditText) rootView.findViewById(R.id.edtViewNewPass);
        edtViewConfirmNewPass = (EditText) rootView.findViewById(R.id.edtViewConfirmNewPass);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        btnSetPass = (Button) rootView.findViewById(R.id.btnSetPass);
    }

    private void setTopbar() {

        ((SellingBaseActivity) mContext).setTopbarTitle(
                getString(R.string.title_create_pass));
    }

    private boolean isValidDetails() {
        String pass, confirmPass;
        pass = edtViewNewPass.getText().toString().trim();
        confirmPass = edtViewConfirmNewPass.getText().toString().trim();

        if (edtViewMobile.getText().toString().trim().isEmpty()) {
            showToast(getString(R.string.enter_mo_no));
            return false;
        } else if (pass.isEmpty()) {
            showToast(getString(R.string.enter_new_pass));
            edtViewNewPass.requestFocus();
            return false;
        } else if (confirmPass.isEmpty()) {
            showToast(getString(R.string.enter_confirm_pass));
            edtViewConfirmNewPass.requestFocus();
            return false;
        } else if (!pass.equalsIgnoreCase(confirmPass)) {
            showToast(getString(R.string.pass_not_matched));
            return false;
        }
        return true;
    }
}
