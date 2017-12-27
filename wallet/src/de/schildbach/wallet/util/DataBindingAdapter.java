package de.schildbach.wallet.util;

import android.databinding.BindingAdapter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;

import hashengineering.darkcoin.wallet.R;

public class DataBindingAdapter {
    @BindingAdapter("android:src")
    public static void setImageUri(ImageView view, String imageUri) {

        Glide
                .with(view.getContext())
                .load(imageUri)
                .placeholder(R.drawable.ic_account_balance_black_24dp)
                .crossFade()
                .into(view);
    }

    @BindingAdapter("android:src")
    public static void setImageUri(ImageView view, Uri imageUri) {
        view.setImageURI(imageUri);
    }

    @BindingAdapter("android:src")
    public static void setImageDrawable(ImageView view, Drawable drawable) {
        view.setImageDrawable(drawable);
    }

    @BindingAdapter("android:src")
    public static void setImageResource(ImageView imageView, int resource) {
        imageView.setImageResource(resource);
    }

    @BindingAdapter("android:text")
    public static void getDateFormatted(TextView tv, String date) {
        try {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            SimpleDateFormat input =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            String dateString = formatter.format(input.parse(date));
            tv.setText(dateString);
        } catch (Exception e) {
            e.printStackTrace();
        }
        tv.setText(date);
    }
}
