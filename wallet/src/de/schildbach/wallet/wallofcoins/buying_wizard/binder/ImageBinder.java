package de.schildbach.wallet.wallofcoins.buying_wizard.binder;

/**
 * Created by  on 30-Mar-18.
 */

import android.content.Context;
import android.databinding.BindingAdapter;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import de.schildbach.wallet_test.R;


public final class ImageBinder {

    private ImageBinder() {
        //NO-OP
    }

    @BindingAdapter("imageUrl")
    public static void setImageUrl(ImageView imageView, String url) {
        Context context = imageView.getContext();

        Glide.with(context)
                .load(url)
                .placeholder(R.drawable.ic_account_balance_black_24dp)
                .error(R.drawable.ic_account_balance_black_24dp)
                .into(imageView);
    }
}
