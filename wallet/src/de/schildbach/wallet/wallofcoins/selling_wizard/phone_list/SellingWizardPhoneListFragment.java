package de.schildbach.wallet.wallofcoins.selling_wizard.phone_list;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.selling_wizard.SellingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.adapters.SellingWizardPhoneListAdapter;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.RetrofitErrorUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingAPIClient;
import de.schildbach.wallet.wallofcoins.selling_wizard.api.SellingApiConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.common.SellingWizardPhoneUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.contact_details.SellingWizardContactDetailsFragment;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardAuthVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardCreateDeviceVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardPhoneListVO;
import de.schildbach.wallet.wallofcoins.selling_wizard.storage.SharedPreferenceUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingConstants;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.SellingWizardFragmentUtils;
import de.schildbach.wallet_test.R;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 19-Mar-18.
 */

public class SellingWizardPhoneListFragment extends SellingWizardBaseFragment implements View.OnClickListener {

    private final String TAG = "PhoneListFragment";
    private View rootView;
    private RecyclerView recycler_phone_list;
    private Button button_sign_up, button_existing_sign_in;
    private SellingWizardPhoneListFragment fragment;
    private TextView text_no_data;
    private ProgressBar progressBar;
    private String mPassword = "", mSelectedPhone = "";


    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_selling_phone_list, container, false);
        init();
        setListeners();
        setPhoneList();
        return rootView;
    }

    private void init() {
        fragment = this;
        recycler_phone_list = (RecyclerView) rootView.findViewById(R.id.recycler_phone_list);
        button_sign_up = (Button) rootView.findViewById(R.id.button_sign_up);
        button_existing_sign_in = (Button) rootView.findViewById(R.id.button_existing_sign_in);
        text_no_data = (TextView) rootView.findViewById(R.id.text_no_data);
        recycler_phone_list.setLayoutManager(new LinearLayoutManager(mContext));
        progressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
    }

    private void setListeners() {
        button_existing_sign_in.setOnClickListener(this);
        button_sign_up.setOnClickListener(this);
    }

    private void setPhoneList() {

        ArrayList<SellingWizardPhoneListVO> sellingWizardPhoneListVOS = SellingWizardPhoneUtil.getStoredPhoneList();

        HashSet<SellingWizardPhoneListVO> hashSet = new HashSet<>();
        hashSet.addAll(sellingWizardPhoneListVOS);
        sellingWizardPhoneListVOS.clear();
        sellingWizardPhoneListVOS.addAll(hashSet);

        if (sellingWizardPhoneListVOS != null & sellingWizardPhoneListVOS.size() > 0) {
            recycler_phone_list.setAdapter(new SellingWizardPhoneListAdapter(mContext, sellingWizardPhoneListVOS, fragment));
            text_no_data.setVisibility(View.GONE);
        } else
            text_no_data.setVisibility(View.VISIBLE);
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.button_existing_sign_in:
            /*    Bundle bundle = new Bundle();
                bundle.putString(WOCConstants.SCREEN_TYPE, "PhoneListFragment");
                EmailAndPhoneFragment phoneFragment = new EmailAndPhoneFragment();
                phoneFragment.setArguments(bundle);

                ((SellingWizardBaseActivity) mContext).replaceFragment(phoneFragment, true, true);*/
                break;
            case R.id.button_sign_up:
                ((SellingWizardBaseActivity) mContext).replaceFragment(new SellingWizardContactDetailsFragment(),
                        true, true);
                break;
        }
    }

    public void onItemClick(String phone) {
        mSelectedPhone = phone;
        checkAuth();
    }

    /**
     * Method for check authentication type
     */
    private void checkAuth() {
        if (NetworkUtil.isOnline(mContext)) {
            progressBar.setVisibility(View.VISIBLE);

            SellingAPIClient.createService(interceptor, mContext).getAuthToken(mSelectedPhone,
                    SellingApiConstants.WALLOFCOINS_PUBLISHER_ID).enqueue(new Callback<SellingWizardAuthVo>() {
                @Override
                public void onResponse(Call<SellingWizardAuthVo> call, Response<SellingWizardAuthVo> response) {
                    progressBar.setVisibility(View.GONE);
                    if (response.code() == 200) {
                        if (response.body() != null) {

                           /* if (response.body().getAuthSource().equals("password")) {//from wesite
                                showUserPasswordAuthenticationDialog();
                            }*/
                            showUserPasswordAuthenticationDialog();
                        }
                    } else {
                        String error = RetrofitErrorUtil.parseError(response);
                        if (error != null && !error.isEmpty())
                            showToast(error);
                    }
                }

                @Override
                public void onFailure(Call<SellingWizardAuthVo> call, Throwable t) {
                    progressBar.setVisibility(View.GONE);
                    showToast(t.getMessage());
                }
            });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));
    }

    /**
     * User authentication custom dialog for authenticate user using password
     */
    private void showUserPasswordAuthenticationDialog() {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(mContext);
        LayoutInflater inflater = getActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.layout_authenticate_password_wallet_dialog, null);
        dialogBuilder.setView(dialogView);

        final EditText edtPassword = (EditText) dialogView.findViewById(R.id.edt_woc_authenticaion_password);
        TextView txtTitle = (TextView) dialogView.findViewById(R.id.txt_existing_user_dialog_message);
        Button btnLogin = (Button) dialogView.findViewById(R.id.btnLogin);
        Button btnForgotPassword = (Button) dialogView.findViewById(R.id.btnForgotPassword);

        txtTitle.setMovementMethod(LinkMovementMethod.getInstance());

        final AlertDialog alertDialog = dialogBuilder.create();
        alertDialog.show();

        ImageView imgClose = (ImageView) dialogView.findViewById(R.id.imgClose);

        imgClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                alertDialog.dismiss();
            }
        });

        btnForgotPassword.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToUrl(WOCConstants.KEY_FORGOT_PASSWORD_URL);
            }
        });

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPassword = edtPassword.getText().toString().trim();
                if (mPassword.length() > 0) {
                    authorize(mPassword);
                    alertDialog.dismiss();
                } else {
                    showToast(mContext.getString(R.string.password_alert));
                }
            }
        });

    }

    /**
     * Authorized user using password or device code
     *
     * @param password
     */
    private void authorize(final String password) {
        if (NetworkUtil.isOnline(mContext)) {
            HashMap<String, String> hashMap = new HashMap<String, String>();

            if (!TextUtils.isEmpty(password)) {
                hashMap.put(SellingApiConstants.KEY_PASSWORD, password);
            } else {
                hashMap.put(SellingApiConstants.KEY_DEVICECODE, getDeviceCode(mContext));
            }
            if (!TextUtils.isEmpty(SharedPreferenceUtil.getString(SellingConstants.PREF_DEVICE_ID, ""))) {
                hashMap.put(WOCConstants.KEY_DEVICEID, SharedPreferenceUtil.getString(SellingConstants.PREF_DEVICE_ID, ""));
            }
            hashMap.put(SellingApiConstants.KEY_PUBLISHER_ID, SellingApiConstants.WALLOFCOINS_PUBLISHER_ID);

            progressBar.setVisibility(View.VISIBLE);
            SellingAPIClient.createService(interceptor, mContext).authorize(mSelectedPhone, hashMap).
                    enqueue(new Callback<SellingWizardAuthVo>() {
                        @Override
                        public void onResponse(Call<SellingWizardAuthVo> call, Response<SellingWizardAuthVo> response) {
                            progressBar.setVisibility(View.GONE);

                            if (response.body() == null) {
                                try {
                                    if (!TextUtils.isEmpty(password)) {
                                        showAlertPasswordDialog();
                                    } else {
                                        createDevice();
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    showToast(mContext.getString(R.string.try_again));
                                }
                                return;
                            }


                            if (!TextUtils.isEmpty(response.body().getToken())) {

                                SharedPreferenceUtil.putValue(SellingConstants.PREF_TOKEN_ID, response.body().getToken());
                                SharedPreferenceUtil.putValue(SellingConstants.PREF_LOGGED_IN_PHONE, mSelectedPhone);

                            }
                            if (response.body().getEmail() != null)
                                SharedPreferenceUtil.putValue(SellingConstants.PREF_LOGGED_IN_EMAIL, response.body().getEmail());
                            if (!TextUtils.isEmpty(password) &&
                                    TextUtils.isEmpty(SharedPreferenceUtil.getString(SellingConstants.PREF_DEVICE_ID, ""))) {
                                getDevice();
                            } else {
                                ((SellingWizardBaseActivity) mContext).popBackDirect();
                            }

                        }

                        @Override
                        public void onFailure(Call<SellingWizardAuthVo> call, Throwable t) {
                            showToast(t.getMessage());
                            progressBar.setVisibility(View.GONE);
                        }
                    });

        } else
            showToast(mContext.getString(R.string.network_not_avaialable));
    }

    /**
     * Get Devices for Register user with password
     */
    private void getDevice() {
        progressBar.setVisibility(View.VISIBLE);
        SellingAPIClient.createService(interceptor, mContext).getDevice().enqueue(new Callback<List<SellingWizardCreateDeviceVo>>() {
            @Override
            public void onResponse(Call<List<SellingWizardCreateDeviceVo>> call,
                                   Response<List<SellingWizardCreateDeviceVo>> response) {
                progressBar.setVisibility(View.GONE);
                if (response.code() == 200 && response.body() != null) {

                    List<SellingWizardCreateDeviceVo> deviceList = response.body();
                    if (deviceList.size() > 0) {
                        SharedPreferenceUtil.putValue(SellingConstants.PREF_DEVICE_ID, deviceList.get(deviceList.size() - 1).getId() + "");
                        authorize("");
                    } else {
                        createDevice();
                    }
                } else {
                    String error = RetrofitErrorUtil.parseError(response);
                    if (error != null && !error.isEmpty())
                        showToast(error);
                }
            }

            @Override
            public void onFailure(Call<List<SellingWizardCreateDeviceVo>> call, Throwable t) {
                progressBar.setVisibility(View.GONE);
                showToast(t.getMessage());
            }
        });

    }

    /**
     * Method for register new device
     */
    private void createDevice() {
        final HashMap<String, String> hashMap = new HashMap<String, String>();
        hashMap.put(SellingApiConstants.KEY_DEVICE_NAME, SellingApiConstants.DEVICE_NAME);
        hashMap.put(SellingApiConstants.KEY_CODE, getDeviceCode(mContext));
        hashMap.put(SellingApiConstants.KEY_PUBLISHER_ID, SellingApiConstants.WALLOFCOINS_PUBLISHER_ID);
        progressBar.setVisibility(View.VISIBLE);
        SellingAPIClient.createService(interceptor, mContext).createDevice(hashMap).enqueue(new Callback<SellingWizardCreateDeviceVo>() {
            @Override
            public void onResponse(Call<SellingWizardCreateDeviceVo> call, Response<SellingWizardCreateDeviceVo> response) {
                progressBar.setVisibility(View.GONE);
                if (null != response.body() && response.code() < 299) {
                    SharedPreferenceUtil.putValue(SellingConstants.PREF_DEVICE_ID, response.body().getId() + "");

                    authorize("");
                } else {
                    String error = RetrofitErrorUtil.parseError(response);
                    if (error != null && !error.isEmpty())
                        showToast(error);
                }
            }

            @Override
            public void onFailure(Call<SellingWizardCreateDeviceVo> call, Throwable t) {
                progressBar.setVisibility(View.GONE);
                showToast(mContext.getString(R.string.try_again));
            }
        });
    }

    //this method remove animation when user want to clear whole back stack
    @Override
    public Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        if (SellingWizardFragmentUtils.sDisableFragmentAnimations) {
            Animation a = new Animation() {
            };
            a.setDuration(0);
            return a;
        }
        return super.onCreateAnimation(transit, enter, nextAnim);
    }
}