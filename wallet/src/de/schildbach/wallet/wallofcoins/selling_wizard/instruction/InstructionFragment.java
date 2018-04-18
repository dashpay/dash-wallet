package de.schildbach.wallet.wallofcoins.selling_wizard.instruction;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingBaseFragment;
import de.schildbach.wallet_test.R;


/**
 * Created by  on 11-Apr-18.
 */

public class InstructionFragment extends SellingBaseFragment implements View.OnClickListener {

    private View rootView;
    private EditText edtViewCurrRate;
    private Button btnEditRate;
    private TextView txtViewCancle, txtViewSave, txtViewEditCurrRate, txtViewBankName, txtViewNewPass, txtViewAccNum, txtViewPivAvail,
            txtViewAdvancedOption;
    private LinearLayout layoutCancel;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.layout_selling_instruction, container, false);
            init();
            setListeners();
            setTopbar();
            return rootView;
        } else
            return rootView;
    }

    private void init() {

        txtViewCancle = (TextView) rootView.findViewById(R.id.txtViewCancle);
        txtViewBankName = (TextView) rootView.findViewById(R.id.txtViewBankName);
        txtViewNewPass = (TextView) rootView.findViewById(R.id.txtViewNewPass);
        txtViewAccNum = (TextView) rootView.findViewById(R.id.txtViewAccNum);
        txtViewPivAvail = (TextView) rootView.findViewById(R.id.txtViewPivAvail);
        edtViewCurrRate = (EditText) rootView.findViewById(R.id.edtViewCurrRate);
        txtViewAdvancedOption = (TextView) rootView.findViewById(R.id.txtViewAdvancedOption);

        btnEditRate = (Button) rootView.findViewById(R.id.btnEditRate);

        txtViewCancle = (TextView) rootView.findViewById(R.id.txtViewCancle);
        txtViewSave = (TextView) rootView.findViewById(R.id.txtViewSave);
        txtViewEditCurrRate = (TextView) rootView.findViewById(R.id.txtViewEditCurrRate);
        layoutCancel = (LinearLayout) rootView.findViewById(R.id.layoutCancel);
    }

    private void setListeners() {
        btnEditRate.setOnClickListener(this);

        txtViewCancle.setOnClickListener(this);
        txtViewSave.setOnClickListener(this);
        txtViewEditCurrRate.setOnClickListener(this);
        txtViewAdvancedOption.setOnClickListener(this);

    }

    private void setTopbar() {
        ((SellingBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_selling));
    }

    @Override
    public void onClick(View view) {

        switch (view.getId()) {
            case R.id.btnEditRate:

                break;
            case R.id.txtViewAdvancedOption:
                showOptionsDialog();
                break;

            case R.id.txtViewSave:
                layoutCancel.setVisibility(View.GONE);
                txtViewEditCurrRate.setVisibility(View.VISIBLE);
                break;
            case R.id.txtViewCancle:
                layoutCancel.setVisibility(View.GONE);
                txtViewEditCurrRate.setVisibility(View.VISIBLE);
                break;
            case R.id.txtViewEditCurrRate:
                edtViewCurrRate.setEnabled(true);
                layoutCancel.setVisibility(View.VISIBLE);
                txtViewEditCurrRate.setVisibility(View.GONE);
                break;
        }
    }

    private void showOptionsDialog() {
        final Dialog dialog = new Dialog(mContext);
        dialog.setContentView(R.layout.layout_selling_options_dialog);


        EditText edtViewMinPayment, edtViewMaxPayment;
        edtViewMinPayment = (EditText) dialog.findViewById(R.id.edtViewMinPayment);
        edtViewMaxPayment = (EditText) dialog.findViewById(R.id.edtViewMaxPayment);
        dialog.show();
    }

}



