package de.schildbach.wallet.wallofcoins;

import android.content.Context;
import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.net.Uri;
import android.os.Build;
import android.support.v7.widget.RecyclerView;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.text.NumberFormat;
import java.text.ParseException;
import java.util.List;
import java.util.Locale;

import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.wallofcoins.response.GetOffersResp;
import de.schildbach.wallet_test.R;
import de.schildbach.wallet_test.databinding.BuyDashOffersItemBinding;
import de.schildbach.wallet_test.databinding.BuyDashOffersItemDoubleBinding;

public class BuyDashOffersAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private Context context;
    private List<GetOffersResp.SingleDepositBean> singleDepositBeenList;
    private List<GetOffersResp.DoubleDepositBean> doubleDeposit;
    private AdapterView.OnItemSelectedListener onItemSelectedListener;
    private String offerAmount;
    private boolean incremented;

    public BuyDashOffersAdapter(Context context, GetOffersResp getOffersResp,String offerAmount,
                                AdapterView.OnItemSelectedListener onItemSelectedListener) {
        this.context = context;
        this.singleDepositBeenList = getOffersResp.singleDeposit;
        this.doubleDeposit = getOffersResp.doubleDeposit;
        if(getOffersResp.multipleBanks!=null && doubleDeposit!=null)
            this.doubleDeposit.addAll(getOffersResp.multipleBanks);
        this.onItemSelectedListener = onItemSelectedListener;
        this.incremented = getOffersResp.incremented;
        this.offerAmount = offerAmount;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup group, int type) {
        LayoutInflater layoutInflater = LayoutInflater.from(context);

        if (type == 1) {
            View v = LayoutInflater.from(context)
                    .inflate(android.R.layout.simple_list_item_1, group, false);
            return new VHolderMore(v);
        } else if (type == 2) {
            BuyDashOffersItemDoubleBinding binding = DataBindingUtil.inflate(layoutInflater, R.layout.buy_dash_offers_item_double, group, false);
            return new VHolderDouble2(binding);
        } else if (type == 3) {
            BuyDashOffersItemBinding binding = DataBindingUtil.inflate(layoutInflater, R.layout.buy_dash_offers_item, group, false);
            return new VHolderDouble1(binding);
        } else {
            BuyDashOffersItemBinding binding = DataBindingUtil.inflate(layoutInflater, R.layout.buy_dash_offers_item, group, false);
            return new VHolderSingle(binding);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, final int position) {

        if (holder instanceof VHolderSingle) {
            final GetOffersResp.SingleDepositBean bean = singleDepositBeenList.get(position - 1);
            final VHolderSingle vholder = (VHolderSingle) holder;
            vholder.binding.setItem(bean);

            if (getNumAmount(bean.deposit.amount) >= 200) {
                vholder.binding.tvItrmOffer2.setText(context.getString(R.string.dotUnicode, bean.amount.dots, GenericUtils.currencySymbol(bean.deposit.currency), getNumAmount(bean.deposit.amount) / getNumAmount(bean.amount.DASH)));
            } else {
                vholder.binding.tvItrmOffer2.setText(context.getString(R.string.dotUnicodeNoRate, bean.amount.dots));
            }

            if(incremented){
                vholder.binding.txtAmount.setVisibility(View.VISIBLE);
            } else {
                vholder.binding.txtAmount.setVisibility(View.INVISIBLE);
            }
            if (bean.bankLogo != null
                    && !bean.bankLogo.equals("")) {
                Glide.with(context)
                        .load(bean.bankLogo)
                        .placeholder(R.drawable.ic_account_balance_black_24dp)
                        .error(R.drawable.ic_account_balance_black_24dp)
                        .into(vholder.binding.ivOffer);
            } else {
                Glide.with(context)
                        .load(bean.bankIcon)
                        .placeholder(R.drawable.ic_account_balance_black_24dp)
                        .error(R.drawable.ic_account_balance_black_24dp)
                        .into(vholder.binding.ivOffer);
            }


            vholder.binding.buttonBuyDashItemOrder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onItemSelectedListener.onItemSelected(null, vholder.binding.getRoot(), position, 0);
                }
            });

            vholder.binding.buttonBuyDashItemLocation.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(bean.bankLocationUrl));
                    context.startActivity(i);
                }
            });

        } else if (holder instanceof VHolderDouble1) {
            GetOffersResp.DoubleDepositBean beanTemp = doubleDeposit.get(position - singleDepositBeenList.size() - 2);
            final GetOffersResp.SingleDepositBean bean = new GetOffersResp.SingleDepositBean();
            bean.deposit = new GetOffersResp.DepositBean();
            bean.amount = new GetOffersResp.AmountBean();

            if (beanTemp.secondOffer != null) {
                bean.amount.BTC = beanTemp.sumAmounts(beanTemp.firstOffer.amount.BTC, beanTemp.secondOffer.amount.BTC);
                bean.amount.bits = beanTemp.sumAmounts(beanTemp.firstOffer.amount.bits, beanTemp.secondOffer.amount.bits);
                bean.amount.DASH = beanTemp.sumAmounts(beanTemp.firstOffer.amount.DASH, beanTemp.secondOffer.amount.DASH);
                bean.amount.dots = beanTemp.sumAmounts(beanTemp.firstOffer.amount.dots, beanTemp.secondOffer.amount.dots);
                bean.deposit.amount = beanTemp.sumAmounts(beanTemp.firstOffer.deposit.amount, beanTemp.secondOffer.deposit.amount);
            } else {
                bean.amount.BTC = beanTemp.firstOffer.amount.BTC;
                bean.amount.bits = beanTemp.firstOffer.amount.bits;
                bean.amount.DASH = beanTemp.firstOffer.amount.DASH;
                bean.amount.dots = beanTemp.firstOffer.amount.dots;
                bean.deposit.amount = beanTemp.firstOffer.deposit.amount;
            }

            bean.bankName = beanTemp.firstOffer.bankName;
            bean.bankLogo = beanTemp.firstOffer.bankLogo;
            bean.address = beanTemp.firstOffer.address;
            bean.distance = beanTemp.firstOffer.distance;
            bean.city = beanTemp.firstOffer.city;
            bean.state = beanTemp.firstOffer.state;
            bean.bankLocationUrl = beanTemp.firstOffer.bankLocationUrl;
            bean.deposit.currency = beanTemp.firstOffer.deposit.currency;


            final VHolderDouble1 vholder = (VHolderDouble1) holder;
            vholder.binding.setItem(bean);

            if (bean.bankLogo != null
                    && !bean.bankLogo.equals("")) {
                Glide.with(context)
                        .load(bean.bankLogo)
                        .placeholder(R.drawable.ic_account_balance_black_24dp)
                        .error(R.drawable.ic_account_balance_black_24dp)
                        .into(vholder.binding.ivOffer);
            } else {
                Glide.with(context)
                        .load(bean.bankIcon)
                        .placeholder(R.drawable.ic_account_balance_black_24dp)
                        .error(R.drawable.ic_account_balance_black_24dp)
                        .into(vholder.binding.ivOffer);
            }

            if (getNumAmount(bean.deposit.amount) >= 200) {
                vholder.binding.tvItrmOffer2.setText(context.getString(R.string.dotUnicode, bean.amount.dots, GenericUtils.currencySymbol(bean.deposit.currency), getNumAmount(bean.deposit.amount) / getNumAmount(bean.amount.DASH)));
            } else {
                vholder.binding.tvItrmOffer2.setText(context.getString(R.string.dotUnicodeNoRate, bean.amount.dots));
            }

            vholder.binding.buttonBuyDashItemOrder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onItemSelectedListener.onItemSelected(null, vholder.binding.getRoot(), position, 0);
                }
            });

        } else if (holder instanceof VHolderDouble2) {
            final GetOffersResp.DoubleDepositBean bean = doubleDeposit.get(position - singleDepositBeenList.size() - 2);
            final VHolderDouble2 vholder = (VHolderDouble2) holder;
            vholder.binding.setItem(bean);


            vholder.binding.buttonBuyDashItemOrder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onItemSelectedListener.onItemSelected(null, vholder.binding.getRoot(), position, 0);
                }
            });

        } else {

            TextView more = (TextView) holder.itemView;
            if (position == 0 && singleDepositBeenList.size() > 0) {
                if (incremented) {
                    more.setText("Below are offers for at least $" + String.format("%.2f", Double.parseDouble(offerAmount)) + ". You must click the ORDER button before you receive instructions to pay at the Cash Payment center.");
                } else {
                    more.setText("Below are offers for $" + singleDepositBeenList.get(0).deposit.amount + ". You must click the ORDER button before you receive instructions to pay at the Cash Payment center.");
                }
            } else {
                more.setText("Best Value Options: More Dash for $" + String.format("%.2f",Double.parseDouble(offerAmount)) + " Cash");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                more.setTextColor(context.getResources().getColor(R.color.colorPrimary, context.getTheme()));
            } else {
                more.setTextColor(context.getResources().getColor(R.color.colorPrimary));
            }
            more.setGravity(Gravity.CENTER);
            more.layout(30, 30, 30, 30);

        }
    }

    private float getNumAmount(String strAmount) {

        float amount = 1;

        try {
            amount = NumberFormat.getNumberInstance(Locale.getDefault()).parse(strAmount).floatValue();
        } catch (ParseException e) {
            e.printStackTrace();
        }

        return amount;
    }


    @Override
    public int getItemCount() {

        int count = singleDepositBeenList.size();

        if (singleDepositBeenList.size() > 0) {
            count++;
        }

        if (null != doubleDeposit && doubleDeposit.size() > 0) {
            count += doubleDeposit.size();
            count++;
        }

        return count;
    }

    @Override
    public int getItemViewType(int position) {
        if ((position == 0 && singleDepositBeenList.size() > 0)
                || (singleDepositBeenList.size() + 1 == position)) {
            return 1;
        }
        if (position > singleDepositBeenList.size() + 1) {
            if (doubleDeposit.get(position - singleDepositBeenList.size() - 2).secondOffer == null
                    || doubleDeposit.get(position - singleDepositBeenList.size() - 2).secondOffer.bankName.equals(doubleDeposit.get(position - singleDepositBeenList.size() - 2).firstOffer.bankName)) {
                return 3;
            } else {
                return 2;
            }
        }
        return 0;
    }

    public class VHolderSingle extends RecyclerView.ViewHolder {
        private BuyDashOffersItemBinding binding;

        public VHolderSingle(BuyDashOffersItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public class VHolderMore extends RecyclerView.ViewHolder {

        public VHolderMore(View v) {
            super(v);
        }
    }

    public class VHolderDouble2 extends RecyclerView.ViewHolder {
        private BuyDashOffersItemDoubleBinding binding;

        public VHolderDouble2(BuyDashOffersItemDoubleBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public class VHolderDouble1 extends RecyclerView.ViewHolder {
        private BuyDashOffersItemBinding binding;

        public VHolderDouble1(BuyDashOffersItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
