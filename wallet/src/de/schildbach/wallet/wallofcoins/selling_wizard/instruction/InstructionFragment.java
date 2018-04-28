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
    private EditText edit_current_rate;
    private Button button_edit_rate;
    private TextView text_cancle, text_save, text_edit_current_rate, text_bank_name,
            text_holder_name, text_acc_number, text_piv_avail,
            text_advanced_option;
    private LinearLayout layout_cancel;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_selling_instruction, container, false);
            init();
            setListeners();
            setTopbar();
            return rootView;
        } else
            return rootView;
    }

    private void init() {

        text_cancle = (TextView) rootView.findViewById(R.id.text_cancle);
        text_bank_name = (TextView) rootView.findViewById(R.id.text_bank_name);
        text_holder_name = (TextView) rootView.findViewById(R.id.text_holder_name);
        text_acc_number = (TextView) rootView.findViewById(R.id.text_acc_number);
        text_piv_avail = (TextView) rootView.findViewById(R.id.text_piv_avail);
        edit_current_rate = (EditText) rootView.findViewById(R.id.edit_current_rate);
        text_advanced_option = (TextView) rootView.findViewById(R.id.text_advanced_option);

        button_edit_rate = (Button) rootView.findViewById(R.id.button_edit_rate);

        text_cancle = (TextView) rootView.findViewById(R.id.text_cancle);
        text_save = (TextView) rootView.findViewById(R.id.text_save);
        text_edit_current_rate = (TextView) rootView.findViewById(R.id.text_edit_current_rate);
        layout_cancel = (LinearLayout) rootView.findViewById(R.id.layout_cancel);
    }

    private void setListeners() {
        button_edit_rate.setOnClickListener(this);

        text_cancle.setOnClickListener(this);
        text_save.setOnClickListener(this);
        text_edit_current_rate.setOnClickListener(this);
        text_advanced_option.setOnClickListener(this);

    }

    private void setTopbar() {
        ((SellingBaseActivity) mContext).setTopbarTitle(
                mContext.getString(R.string.title_selling));
    }

    @Override
    public void onClick(View view) {

        switch (view.getId()) {
            case R.id.button_edit_rate:

                break;
            case R.id.text_advanced_option:
                showOptionsDialog();
                break;

            case R.id.text_save:
                edit_current_rate.setEnabled(false);
                layout_cancel.setVisibility(View.GONE);
                text_edit_current_rate.setVisibility(View.VISIBLE);
                break;
            case R.id.text_cancle:
                layout_cancel.setVisibility(View.GONE);
                text_edit_current_rate.setVisibility(View.VISIBLE);
                break;
            case R.id.text_edit_current_rate:
                edit_current_rate.setEnabled(true);
                layout_cancel.setVisibility(View.VISIBLE);
                text_edit_current_rate.setVisibility(View.GONE);
                break;
        }
    }

    private void showOptionsDialog() {
        final Dialog dialog = new Dialog(mContext);
        dialog.setContentView(R.layout.dialog_selling_options);


        EditText edit_min_payment, edit_max_payment;
        edit_min_payment = (EditText) dialog.findViewById(R.id.edit_min_payment);
        edit_max_payment = (EditText) dialog.findViewById(R.id.edit_max_payment);

        Button button_cancle, button_save;

        button_cancle = (Button) dialog.findViewById(R.id.button_cancle);
        button_save = (Button) dialog.findViewById(R.id.button_save);
        button_cancle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        button_save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

}



