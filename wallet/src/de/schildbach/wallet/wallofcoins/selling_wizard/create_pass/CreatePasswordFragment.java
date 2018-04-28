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
    private EditText edit_mobile, edit_new_password, edit_confirm_new_password;
    private Button button_set_password;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_selling_create_password, container, false);
            init();
            setTopbar();
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        edit_mobile = (EditText) rootView.findViewById(R.id.edit_mobile);
        edit_new_password = (EditText) rootView.findViewById(R.id.edit_new_password);
        edit_confirm_new_password = (EditText) rootView.findViewById(R.id.edit_confirm_new_password);
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        button_set_password = (Button) rootView.findViewById(R.id.button_set_password);
    }

    private void setTopbar() {

        ((SellingBaseActivity) mContext).setTopbarTitle(
                getString(R.string.title_create_pass));
    }

    private boolean isValidDetails() {
        String pass, confirmPass;
        pass = edit_new_password.getText().toString().trim();
        confirmPass = edit_confirm_new_password.getText().toString().trim();

        if (edit_mobile.getText().toString().trim().isEmpty()) {
            showToast(getString(R.string.enter_mo_no));
            return false;
        } else if (pass.isEmpty()) {
            showToast(getString(R.string.enter_new_pass));
            edit_new_password.requestFocus();
            return false;
        } else if (confirmPass.isEmpty()) {
            showToast(getString(R.string.enter_confirm_pass));
            edit_confirm_new_password.requestFocus();
            return false;
        } else if (!pass.equalsIgnoreCase(confirmPass)) {
            showToast(getString(R.string.pass_not_matched));
            return false;
        }
        return true;
    }
}
