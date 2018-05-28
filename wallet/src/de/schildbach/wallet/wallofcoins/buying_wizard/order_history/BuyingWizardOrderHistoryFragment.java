package de.schildbach.wallet.wallofcoins.buying_wizard.order_history;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseActivity;
import de.schildbach.wallet.wallofcoins.buying_wizard.BuyingWizardBaseFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.adapters.BuyingWizardOrderListAdapter;
import de.schildbach.wallet.wallofcoins.buying_wizard.location.BuyingWizardLocationFragment;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.BuyingWizardFragmentUtils;
import de.schildbach.wallet.wallofcoins.buying_wizard.utils.NetworkUtil;
import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.ConfirmDepositResp;
import de.schildbach.wallet.wallofcoins.response.ErrorResp;
import de.schildbach.wallet.wallofcoins.response.OrderListResp;
import de.schildbach.wallet_test.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created on 08-Mar-18.
 */

public class BuyingWizardOrderHistoryFragment extends BuyingWizardBaseFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener, View.OnClickListener {

    private final String TAG = "OrderHistoryFragment";
    private View rootView;
    private RecyclerView rv_order_list;
    private LinearLayout linearProgress, layoutOrderHistory, layoutInstruction, layoutLogout;
    private Button btn_buy_more, btnSignout, btnWebLink;
    private boolean isFromCreateHold;
    private BuyingWizardOrderHistoryFragment fragment;
    private int countdownInterval = 1000;
    private TextView text_email_receipt, text_no_order, text_message, textHelpMessage;
    private Handler handler;
    private MyRunnable myRunnable;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mContext = context;

    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (rootView == null) {
            rootView = inflater.inflate(R.layout.fragment_buying_order_history, container, false);
            init();
            setListeners();
            readBundle(getArguments());
            getOrderList(isFromCreateHold);
            return rootView;
        } else
            return rootView;
    }

    private void init() {
        fragment = this;
        rv_order_list = (RecyclerView) rootView.findViewById(R.id.rv_order_list);
        linearProgress = (LinearLayout) rootView.findViewById(R.id.linear_progress);
        layoutOrderHistory = (LinearLayout) rootView.findViewById(R.id.layout_order_list);
        layoutInstruction = (LinearLayout) rootView.findViewById(R.id.lay_help_instruction_order);
        layoutLogout = (LinearLayout) rootView.findViewById(R.id.lay_logout_order);
        text_message = (TextView) rootView.findViewById(R.id.text_message);
        textHelpMessage = (TextView) rootView.findViewById(R.id.text_help_message);
        btnSignout = (Button) rootView.findViewById(R.id.btn_signout);
        btnWebLink = (Button) rootView.findViewById(R.id.btn_WebLink);
        btn_buy_more = (Button) rootView.findViewById(R.id.btn_buy_more);
        text_email_receipt = (TextView) rootView.findViewById(R.id.text_email_receipt);
        text_no_order = (TextView) rootView.findViewById(R.id.text_no_order_history);
    }

    private void setListeners() {
        btn_buy_more.setOnClickListener(this);
        btnSignout.setOnClickListener(this);
        btnWebLink.setOnClickListener(this);
    }

    private void readBundle(Bundle bundle) {
        if (bundle != null && bundle.containsKey("isFromCreateHold"))
            isFromCreateHold = bundle.getBoolean("isFromCreateHold");
    }

    /**
     * Get order list using auth token
     *
     * @param isFromCreateHold
     */
    private void getOrderList(final boolean isFromCreateHold) {
        if (NetworkUtil.isOnline(mContext)) {
            linearProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, mContext)
                    .getOrders(getString(R.string.WALLOFCOINS_PUBLISHER_ID))
                    .enqueue(new Callback<List<OrderListResp>>() {
                        @Override
                        public void onResponse(Call<List<OrderListResp>> call, Response<List<OrderListResp>> response) {
                            linearProgress.setVisibility(View.GONE);
                            if (response.code() == 200 && response.body() != null) {
                                Log.d(TAG, "onResponse: boolean==>" + isFromCreateHold);
                                if (response.body() != null && response.body().size() > 0) {
                                    if (isFromCreateHold) {
                                        List<OrderListResp> orderList = new ArrayList<>();
                                        for (OrderListResp orderListResp : response.body()) {
                                            if (orderListResp.status.equals("WD")) {
                                                Log.d(TAG, "onResponse: status==>" + orderListResp.status);
                                                orderList.add(orderListResp);
                                                break;
                                            }
                                        }
                                        if (orderList.size() > 0) {
                                            manageOrderList(orderList, isFromCreateHold);
                                        } else {
                                            manageOrderList(response.body(), isFromCreateHold);
                                        }
                                    } else {
                                        manageOrderList(response.body(), false);
                                    }
                                } else {
                                    blankOrderHistory();
                                }
                            } else if (response.code() == 403) {
                                ((BuyingWizardBaseActivity) mContext).removeAllFragmentFromStack();
                                navigateToLocationScreen();
                            }
                        }

                        @Override
                        public void onFailure(Call<List<OrderListResp>> call, Throwable t) {
                            linearProgress.setVisibility(View.GONE);
                            Log.e(TAG, "onFailure: ", t);
                            showToast(mContext.getString(R.string.try_again));
                        }
                    });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    /**
     * Method for manage order list
     *
     * @param orderList
     * @param isFromCreateHold
     */
    private void manageOrderList(final List<OrderListResp> orderList, boolean isFromCreateHold) {

        if (orderList.size() > 0) {
            for (OrderListResp orderListResp : orderList) {
                if (orderListResp.status.equals("WD")) {
                    btn_buy_more.setVisibility(View.GONE);
                    break;
                } else {
                    btn_buy_more.setVisibility(View.VISIBLE);
                }
            }

            int lastWDV = -1;
            for (int i = 0; i < orderList.size(); i++) {
                if (orderList.get(i).status.equals("WD")) {
                    if (i != 0) {
                        OrderListResp tempOrder = orderList.get(i);
                        orderList.remove(i);
                        orderList.add(0, tempOrder);
                        lastWDV = 0;
                    } else {
                        lastWDV = i;
                    }

                }
            }

            OrderListResp orderListResp1 = new OrderListResp();
            orderListResp1.id = -1;
            orderList.add(lastWDV + 1, orderListResp1);

            OrderListResp orderListResp = new OrderListResp();
            orderListResp.id = -2;
            orderList.add(lastWDV + 2, orderListResp);

            if (orderList.size() - 2 == 1 && orderList.get(0).status != null && orderList.get(0).status.equals("WD")) {
                isFromCreateHold = true;
            }

            if (!isFromCreateHold) {
                OrderListResp orderListResp2 = new OrderListResp();
                orderListResp2.id = -3;
                orderList.add(lastWDV + 3, orderListResp2);
            }


            if (orderList.size() == 1 && orderList.get(0).status.equals("WD")) {
                text_email_receipt.setVisibility(View.GONE);
            } else {

                text_email_receipt.setVisibility(View.VISIBLE);
                text_email_receipt.setText(Html.fromHtml(getString(R.string.text_send_email_receipt)));

                text_email_receipt.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                                "mailto", WOCConstants.SUPPORT_EMAIL, null));
                        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{WOCConstants.SUPPORT_EMAIL});
                        if (orderList.size() > 0)
                            emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Order #{" + orderList.get(0).id + "} - {" +
                                    ((BuyingWizardBaseActivity) mContext).buyDashPref.getPhone() + "}.");
                        emailIntent.putExtra(Intent.EXTRA_TEXT, "");
                        startActivity(Intent.createChooser(emailIntent, WOCConstants.SEND_EMAIL));
                    }
                });
            }

            LinearLayoutManager linearLayoutManager = new LinearLayoutManager(mContext);
            rv_order_list.setLayoutManager(linearLayoutManager);
            rv_order_list.setAdapter(new BuyingWizardOrderListAdapter(mContext, orderList, fragment,
                    ((BuyingWizardBaseActivity) mContext)
                            .buyDashPref));
        } else {
            blankOrderHistory();
        }
    }

    private void navigateToLocationScreen() {
        ((BuyingWizardBaseActivity) mContext)
                .replaceFragment(new BuyingWizardLocationFragment(), true, false);
    }

    private void blankOrderHistory() {
        layoutOrderHistory.setVisibility(View.VISIBLE);
        text_no_order.setVisibility(View.VISIBLE);
        btn_buy_more.setVisibility(View.VISIBLE);
        layoutLogout.setVisibility(View.VISIBLE);
        layoutInstruction.setVisibility(View.VISIBLE);
        String msg = mContext.getString(R.string.wallet_is_signed) + " " +
                ((BuyingWizardBaseActivity) mContext).buyDashPref.getPhone();
        text_message.setText(msg);
        textHelpMessage.setText(R.string.call_for_help);
    }

    /**
     * hide show view
     */
    public void changeView() {
        if (isFromCreateHold) {
            isFromCreateHold = false;
            getOrderList(isFromCreateHold);
        } else {
            navigateToLocationScreen();
        }
    }

    //runnable for count down timer
    private static class MyRunnable implements Runnable {
        WeakReference<TextView> textDepositeDue1;
        Handler handler;
        String dueDateTime;
        int countdownInterval;

        public MyRunnable(TextView tvText, Handler handler, String dueDateTime, int countdownInterval) {
            this.textDepositeDue1 = new WeakReference<>(tvText);
            this.handler = handler;
            this.dueDateTime = dueDateTime;
            this.countdownInterval = countdownInterval;
        }

        @Override
        public void run() {
            //Save the TextView to a local variable because the weak referenced object could become empty at any time
            TextView textDepositeDue = textDepositeDue1.get();
            handler.postDelayed(this, countdownInterval);
            try {
                //2018-03-21T19:03:51.900811+03:00
                SimpleDateFormat dateFormat = new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSZZZ", Locale.US);
               /* SimpleDateFormat dateFormat = new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSZZZZ");*/
//
                // Here Set your Event Date
                //dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                //Date eventDate = dateFormat.parse(dueDateTime.replace("T", " ").substring(0, 19));
                Date eventDate = dateFormat.parse(dueDateTime);
                Date currentDate = new Date();
                if (!currentDate.after(eventDate)) {
                    long diff = eventDate.getTime()
                            - currentDate.getTime();
                    long hours = diff / (60 * 60 * 1000);
                    diff -= hours * (60 * 60 * 1000);
                    long minutes = diff / (60 * 1000);
                    diff -= minutes * (60 * 1000);
                    long seconds = diff / 1000;

                    if (hours > 0) {
                        textDepositeDue.setText("Deposit Due: " + hours + " hours " + minutes + " minutes");
                        countdownInterval = 60 * 1000; // call in minutes
                    } else {
                        if (minutes < 10) {
                            textDepositeDue.setTextColor(Color.parseColor("#DD0000"));
                        } else {
                            textDepositeDue.setTextColor(Color.parseColor("#000000"));
                        }
                        textDepositeDue.setText("Deposit Due: " + minutes + " minutes " + seconds + " seconds");
                        countdownInterval = 1000; // call in seconds
                    }
                } else {
                    textDepositeDue.setText(R.string.deposit_due_zero);
                    handler.removeMessages(0);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (handler != null) {
            handler.removeCallbacks(myRunnable);
            myRunnable = null;
        }

    }

    /**
     * Count down timer for Hold Expire status
     *
     * @param dueDateTime
     * @param textDepositeDue
     */
    public void countDownStart(final String dueDateTime, final TextView textDepositeDue) {
        Log.e(TAG, "countDownStart: " + dueDateTime);
        countdownInterval = 1000;

        if (handler == null) {
            handler = new Handler();
            myRunnable = new MyRunnable(textDepositeDue, handler, dueDateTime, countdownInterval);
            handler.postDelayed(myRunnable, 0);
        }
    }

    /**
     * Method for singout user
     *
     * @param isPendingHold
     */
    public void deleteAuthCall(final boolean isPendingHold) {
        if (NetworkUtil.isOnline(mContext)) {
            final String phone = ((BuyingWizardBaseActivity) mContext).buyDashPref.getPhone();
            if (!TextUtils.isEmpty(phone)) {
                linearProgress.setVisibility(View.VISIBLE);
                //password = "";
                WallofCoins.createService(interceptor, mContext)
                        .deleteAuth(phone, getString(R.string.WALLOFCOINS_PUBLISHER_ID))
                        .enqueue(new Callback<CheckAuthResp>() {
                            @Override
                            public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                                Log.d(TAG, "onResponse: response code==>>" + response.code());
                                linearProgress.setVisibility(View.GONE);
                                if (response.code() < 299) {
                                    ((BuyingWizardBaseActivity) mContext).buyDashPref.setAuthToken("");
                                    //  password = "";
                                    ((BuyingWizardBaseActivity) mContext).buyDashPref.clearAllPrefrance();
                                    if (isPendingHold) {
                                    } else {
                                        showToast(mContext.getString(R.string.alert_sign_out));
                                        navigateToLocationScreen();
                                    }
                                } else {
                                    showToast(mContext.getString(R.string.try_again));
                                }
                            }

                            @Override
                            public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                                linearProgress.setVisibility(View.GONE);
                                showToast(mContext.getString(R.string.try_again));
                            }
                        });
            } else {
                showToast(mContext.getString(R.string.alert_phone));
            }
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    public void goToGivenUrl(String url) {
        goToUrl(url);
    }

    public void cancelHoldOrder(final OrderListResp orderListResp) {
        AlertDialog.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder = new AlertDialog.Builder(mContext, android.R.style.Theme_Material_Dialog_Alert);
        } else {
            builder = new AlertDialog.Builder(mContext);
        }
        builder.setTitle(getString(R.string.deposit_cancel_confirmation_title))
                .setMessage(getString(R.string.deposit_cancel_confirmation_message))
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cancelOrder("" + orderListResp.id);
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    public void depositFinished(final OrderListResp orderListResp) {
        hideKeyBoard();
        AlertDialog.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder = new AlertDialog.Builder(mContext, android.R.style.Theme_Material_Dialog_Alert);
        } else {
            builder = new AlertDialog.Builder(mContext);
        }
        builder.setTitle(getString(R.string.deposit_finish_confirmation_title))
                .setMessage(getString(R.string.deposit_finish_confirmation_message))
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        CaptureHoldResp response = new CaptureHoldResp();
                        response.id = orderListResp.id;
                        response.total = orderListResp.total;
                        response.payment = orderListResp.payment;
                        response.paymentDue = orderListResp.paymentDue;
                        response.bankName = orderListResp.bankName;
                        response.nameOnAccount = orderListResp.nameOnAccount;
                        response.account = orderListResp.account;
                        response.status = orderListResp.status;
                        CaptureHoldResp.NearestBranchBean nearestBranchBean = new CaptureHoldResp.NearestBranchBean();
                        if (orderListResp.nearestBranch != null) {
                            nearestBranchBean.name = orderListResp.nearestBranch.name;
                            nearestBranchBean.city = orderListResp.nearestBranch.city;
                            nearestBranchBean.state = orderListResp.nearestBranch.state;
                            nearestBranchBean.phone = orderListResp.nearestBranch.phone;
                            nearestBranchBean.address = orderListResp.nearestBranch.address;
                            response.nearestBranch = nearestBranchBean;
                        }
                        response.bankUrl = orderListResp.account;
                        response.bankLogo = orderListResp.account;
                        response.bankIcon = orderListResp.account;
                        response.bankIconHq = orderListResp.account;
                        response.privateId = orderListResp.account;
                        confirmDeposit(response);
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    /**
     * Method call for Cancel order with status code "WD"
     *
     * @param orderId
     */
    private void cancelOrder(String orderId) {
        if (NetworkUtil.isOnline(mContext)) {
            linearProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, mContext).cancelOrder(orderId, getString(R.string.WALLOFCOINS_PUBLISHER_ID)).enqueue(new Callback<Void>() {
                @Override
                public void onResponse(Call<Void> call, Response<Void> response) {
                    linearProgress.setVisibility(View.GONE);
                    if (response.code() == 204) {
                        showToast(mContext.getString(R.string.alert_cancel_order));
                        navigateToLocationScreen();
                    } else {
                        showToast(mContext.getString(R.string.try_again));
                    }
                }

                @Override
                public void onFailure(Call<Void> call, Throwable t) {
                    Log.e(TAG, "onFailure: ", t);
                    linearProgress.setVisibility(View.GONE);
                    showToast(mContext.getString(R.string.try_again));
                }
            });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    /**
     * Method call for confirm order deposit amount
     *
     * @param response
     */
    private void confirmDeposit(CaptureHoldResp response) {
        if (NetworkUtil.isOnline(mContext)) {
            linearProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, getActivity()).confirmDeposit("" + response.id, "", getString(R.string.WALLOFCOINS_PUBLISHER_ID))
                    .enqueue(new Callback<ConfirmDepositResp>() {
                @Override
                public void onResponse(Call<ConfirmDepositResp> call, Response<ConfirmDepositResp> response) {
                    linearProgress.setVisibility(View.GONE);

                    if (null != response && null != response.body()) {
                        showToast(mContext.getString(R.string.alert_payment_done));
                        getOrderList(false);
                    } else if (null != response && null != response.errorBody()) {
                        try {
                            ErrorResp errorResp = new Gson().fromJson(response.errorBody().string(), ErrorResp.class);
                            Toast.makeText(getContext(), errorResp.detail, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            e.printStackTrace();
                            showToast(mContext.getString(R.string.try_again));
                        }

                    } else {
                        showToast(mContext.getString(R.string.try_again));
                    }
                }

                @Override
                public void onFailure(Call<ConfirmDepositResp> call, Throwable t) {
                    linearProgress.setVisibility(View.GONE);
                    Log.e(TAG, "onFailure: ", t);
                    showToast(mContext.getString(R.string.try_again));
                }
            });
        } else
            showToast(mContext.getString(R.string.network_not_avaialable));

    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {

    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.btn_buy_more:
                navigateToLocationScreen();
                break;
            case R.id.btn_signout:
                deleteAuthCall(false);
                break;
            case R.id.btn_WebLink:
                goToGivenUrl(WOCConstants.KEY_WEB_URL);
                break;
        }
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
