package de.schildbach.wallet.wallofcoins.buyingwizard.adapters;

import android.content.Context;
import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.os.Handler;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.Timer;

import de.schildbach.wallet.wallofcoins.BuyDashPref;
import de.schildbach.wallet.wallofcoins.WOCConstants;
import de.schildbach.wallet.wallofcoins.buyingwizard.models.AccountJson;
import de.schildbach.wallet.wallofcoins.buyingwizard.order_history.OrderHistoryFragment;
import de.schildbach.wallet.wallofcoins.response.OrderListResp;
import de.schildbach.wallet_test.R;
import de.schildbach.wallet_test.databinding.ItemOrderListBinding;

/**
 * Created on 08-Mar-18.
 */

public class OrderListAdapter extends RecyclerView.Adapter<OrderListAdapter.VHolder> {

    private Context mContext;
    private final List<OrderListResp> orderList;
    private OrderHistoryFragment historyFragment;
    private BuyDashPref buyDashPref;

    public OrderListAdapter(Context mContext, List<OrderListResp> orderList, OrderHistoryFragment historyFragment, BuyDashPref buyDashPref) {
        this.mContext = mContext;
        this.orderList = orderList;
        this.historyFragment = historyFragment;
        this.buyDashPref = buyDashPref;
    }

    @Override
    public OrderListAdapter.VHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        ItemOrderListBinding itemBinding = DataBindingUtil.inflate(layoutInflater, R.layout.item_order_list, parent, false);
        return new OrderListAdapter.VHolder(itemBinding);
    }

    @Override
    public void onBindViewHolder(OrderListAdapter.VHolder holder, int position) {
        final OrderListResp orderListResp = orderList.get(position);
        if (orderListResp.id != -1 && orderListResp.id != -2 && orderListResp.id != -3) {

            holder.itemBinding.layLogout.setVisibility(View.GONE);
            holder.itemBinding.layHelpInstruction.setVisibility(View.GONE);
            holder.itemBinding.layOrderHistory.setVisibility(View.GONE);
            holder.itemBinding.layoutCompletionDetail.setVisibility(View.VISIBLE);
            holder.itemBinding.setItem(orderListResp);


            Type listType = new TypeToken<ArrayList<AccountJson>>() {
            }.getType();

            holder.itemBinding.linearAccountDetail.removeAllViews();
            try {
                ArrayList<AccountJson> accountList = new Gson().fromJson(orderListResp.account, listType);

                if (accountList != null && orderListResp.status.equals("WD")) {
                    for (int i = 0; i < accountList.size(); i++) {
                        TextView textView = new TextView(mContext);
                        textView.setTextSize(16);
                        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                        layoutParams.topMargin = 0;
                        textView.setLayoutParams(layoutParams);
                        textView.setText(accountList.get(i).getLabel() + ": " + accountList.get(i).getValue());
                        holder.itemBinding.linearAccountDetail.addView(textView);
                    }
                    holder.itemBinding.textAccountNo.setVisibility(View.GONE);
                    holder.itemBinding.textNameAccount.setVisibility(View.GONE);

                } else {
                    holder.itemBinding.linearAccountDetail.setVisibility(View.GONE);
                }
            } catch (JsonSyntaxException e) {
                e.printStackTrace();
            }

            if (orderListResp.account == null
                    || orderListResp.account.equals("")) {
                holder.itemBinding.textAccountNo.setVisibility(View.GONE);
            }
            if (orderListResp.nameOnAccount == null
                    || orderListResp.nameOnAccount.equals("")) {
                holder.itemBinding.textNameAccount.setVisibility(View.GONE);
            }

            holder.itemBinding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    historyFragment.depositFinished(orderListResp);
                }
            });

            holder.itemBinding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    historyFragment.cancelHoldOrder(orderListResp);
                }
            });


            if (orderListResp.nearestBranch != null) {
                if ((orderListResp.nearestBranch.address == null
                        || orderListResp.nearestBranch.address.equals(""))
                        && orderListResp.status.equals("WD")
                        ) {
                    holder.itemBinding.buttonBuyDashItemLocation.setVisibility(View.VISIBLE);
                    holder.itemBinding.tvItrmOffer4.setVisibility(View.VISIBLE);
                } else {
                    holder.itemBinding.buttonBuyDashItemLocation.setVisibility(View.GONE);
                    holder.itemBinding.tvItrmOffer4.setVisibility(View.GONE);
                }
            }

            holder.itemBinding.buttonBuyDashItemLocation.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    historyFragment.goToGivenUrl(orderListResp.bankUrl);
                }
            });

//              you must deposit cash
            double dots = Double.parseDouble(orderListResp.total) * 1000000;
            DecimalFormat formatter = new DecimalFormat("#,###,###.##");
            String yourFormattedDots = formatter.format(dots);

            if (orderListResp.bankLogo != null
                    && !orderListResp.bankLogo.equals("")) {
                Glide.with(mContext)
                        .load(orderListResp.bankLogo)
                        .placeholder(R.drawable.ic_account_balance_black_24dp)
                        .error(R.drawable.ic_account_balance_black_24dp)
                        .into(holder.itemBinding.imageBank);
            } else {
                holder.itemBinding.imageBank.setImageResource(R.drawable.ic_account_balance_black_24dp);
            }
            //Log.e(TAG, "onBindViewHolder: " + orderListResp.status);

            if (orderListResp.status.equals("WD")) {

                holder.itemBinding.orderDash.setText("Total Dash: " + orderListResp.total + " (" + yourFormattedDots + " dots)\n"
                        + "You must deposit cash at the above Payment Center. Additional fees may apply. Paying in another method other than cash may delay your order.");
                holder.itemBinding.orderDashInstruction.setVisibility(View.VISIBLE);
                holder.itemBinding.btnCancelOrder.setVisibility(View.VISIBLE);
                holder.itemBinding.btnDepositFinished.setVisibility(View.VISIBLE);
                holder.itemBinding.layoutDueDate.setVisibility(View.VISIBLE);

                holder.itemBinding.textPaymentDueDate.setVisibility(View.VISIBLE);
                historyFragment.countDownStart(orderListResp.paymentDue, holder.itemBinding.textPaymentDueDate);


                /*if (holder.timer != null) {
                    holder.timer.cancel();
                }
                holder.timer = new Timer();
                holder.timer.scheduleAtFixedRate(new TimerTask() {
                                                     @Override
                                                     public void run() {
                                                         //Called each time when 1000 milliseconds (1 second) (the period parameter)
                                                         startTimer(holder.itemBinding.textPaymentDueDate, holder.mHandler,
                                                                 orderListResp.paymentDue, 1000, this);
                                                     }
                                                 },
                        0,
                        1000);*/


                /* if (holder.mHandler != null) {
                    holder.mHandler.removeCallbacks(holder.mRunnable);
                }
                holder.mHandler = new Handler();
                holder.mRunnable = new Runnable() {
                    @Override
                    public void run() {
                        startTimer(holder.itemBinding.textPaymentDueDate, holder.mHandler,
                                orderListResp.paymentDue, 1000, this);
                    }
                };
                holder.mHandler.postDelayed(holder.mRunnable, 100);*/


                holder.itemBinding.textContactInstruction.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // goToUrl(WOCConstants.KEY_WEB_URL);
                        historyFragment.goToGivenUrl(WOCConstants.KEY_WEB_URL);
                    }
                });

            } else {

                holder.itemBinding.orderDash.setText("Total Dash: " + orderListResp.total + " (" + yourFormattedDots + " dots)");
                holder.itemBinding.layoutDueDate.setVisibility(View.GONE);
                holder.itemBinding.textPaymentDueDate.setVisibility(View.GONE);
                holder.itemBinding.orderDashInstruction.setVisibility(View.GONE);
                holder.itemBinding.btnCancelOrder.setVisibility(View.GONE);
                holder.itemBinding.btnDepositFinished.setVisibility(View.GONE);
                holder.itemBinding.textContactInstruction.setVisibility(View.GONE);
                holder.itemBinding.textAccountNo.setVisibility(View.GONE);
                holder.itemBinding.textNameAccount.setVisibility(View.GONE);
            }

            if (orderListResp.status.equals("WD")) {
                holder.itemBinding.textTransactionStatus.setText(mContext.getString(R.string.status_waiting_deposit));
            } else if (orderListResp.status.equals("WDV")) {
                holder.itemBinding.textTransactionStatus.setText(mContext.getString(R.string.status_waiting_deposit_verification));
            } else if (orderListResp.status.equals("RERR")) {
                holder.itemBinding.textTransactionStatus.setText(mContext.getString(R.string.status_issue_with_receipt));
            } else if (orderListResp.status.equals("DERR")) {
                holder.itemBinding.textTransactionStatus.setText(mContext.getString(R.string.status_issue_with_deposit));
            } else if (orderListResp.status.equals("RSD")) {
                holder.itemBinding.textTransactionStatus.setText(mContext.getString(R.string.status_reserved_for_deposit));
            } else if (orderListResp.status.equals("RMIT")) {
                holder.itemBinding.textTransactionStatus.setText(mContext.getString(R.string.status_remit_address_missing));
            } else if (orderListResp.status.equals("UCRV")) {
                holder.itemBinding.textTransactionStatus.setText(mContext.getString(R.string.status_under_review));
            } else if (orderListResp.status.equals("PAYP")) {
                holder.itemBinding.textTransactionStatus.setText(mContext.getString(R.string.status_done_pending_delivery));
            } else if (orderListResp.status.equals("SENT")) {
                holder.itemBinding.textTransactionStatus.setText(mContext.getString(R.string.status_done_units_delivered));
            }

        } else if (orderListResp.id == -3) {

            holder.itemBinding.layoutCompletionDetail.setVisibility(View.GONE);
            holder.itemBinding.layHelpInstruction.setVisibility(View.GONE);
            holder.itemBinding.layLogout.setVisibility(View.GONE);
            holder.itemBinding.layOrderHistory.setVisibility(View.VISIBLE);

        } else if (orderListResp.id == -2) {

            holder.itemBinding.layoutCompletionDetail.setVisibility(View.GONE);
            holder.itemBinding.layHelpInstruction.setVisibility(View.GONE);
            holder.itemBinding.layLogout.setVisibility(View.VISIBLE);
            holder.itemBinding.layOrderHistory.setVisibility(View.GONE);

            holder.itemBinding.textMessage.setText(mContext.getString(R.string.wallet_is_signed) + " " +
                    buyDashPref.getPhone());

            holder.itemBinding.btnSignout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    historyFragment.deleteAuthCall(false);
                }
            });


        } else if (orderListResp.id == -1) {

            holder.itemBinding.layoutCompletionDetail.setVisibility(View.GONE);
            holder.itemBinding.layHelpInstruction.setVisibility(View.VISIBLE);
            holder.itemBinding.layLogout.setVisibility(View.GONE);
            holder.itemBinding.layOrderHistory.setVisibility(View.GONE);

            holder.itemBinding.textHelpMessage.setText(R.string.call_for_help);
            holder.itemBinding.btnWebLink.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    //goToUrl(WOCConstants.KEY_WEB_URL);
                    historyFragment.goToGivenUrl(WOCConstants.KEY_WEB_URL);
                }
            });
        }

    }
    public void removeHandlerCallBack()
    {

    }
    private void startTimer(TextView textDepositeDue, Handler handler, String dueDateTime, int countdownInterval,
                            Runnable runnable) {
        //handler.postDelayed(runnable, countdownInterval);
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss");
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            // Here Set your Event Date
            Date eventDate = dateFormat.parse(dueDateTime.replace("T", " ").substring(0, 19));
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
                    countdownInterval = 60 * 1000;
                } else {
                    if (minutes < 10) {
                        textDepositeDue.setTextColor(Color.parseColor("#DD0000"));
                    } else {
                        textDepositeDue.setTextColor(Color.parseColor("#000000"));
                    }
                    textDepositeDue.setText("Deposit Due: " + minutes + " minutes " + seconds + " seconds");
                    countdownInterval = 1000;
                }
            } else {
                textDepositeDue.setText("Deposit Due: 0 minutes 0 seconds");
                //handler.removeMessages(0);
            }
        } catch (Exception e) {
        }
    }

    @Override
    public int getItemCount() {
        return orderList.size();
    }

    public class VHolder extends RecyclerView.ViewHolder {
        private ItemOrderListBinding itemBinding;
        private Handler mHandler;
        private Runnable mRunnable;
        private Timer timer;

        public VHolder(ItemOrderListBinding itemBinding) {
            super(itemBinding.getRoot());
            this.itemBinding = itemBinding;
        }
    }
}
