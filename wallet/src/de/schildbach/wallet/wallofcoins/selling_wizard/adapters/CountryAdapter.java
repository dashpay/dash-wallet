package de.schildbach.wallet.wallofcoins.selling_wizard.adapters;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

import de.schildbach.wallet.wallofcoins.response.CountryData;
import de.schildbach.wallet_test.R;


public class CountryAdapter extends ArrayAdapter<CountryData.CountriesBean> {

    private Activity activity;
    private List<CountryData.CountriesBean> data;
    private LayoutInflater inflater;

    public CountryAdapter(
            Activity activitySpinner,
            int textViewResourceId,
            List<CountryData.CountriesBean> objects
    ) {
        super(activitySpinner, textViewResourceId, objects);

        /********** Take passed values **********/
        activity = activitySpinner;
        data = objects;

        /***********  Layout inflator to call external xml layout () **********************/
        inflater = (LayoutInflater) activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
        return getCustomView(position, convertView, parent);
    }

    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        return getCustomView(position, convertView, parent);
    }

    // This funtion called for each row ( Called data.size() times )
    public View getCustomView(int position, View convertView, ViewGroup parent) {

        /********** Inflate spinner_rows.xml file for each row ( Defined below ) ************/
        View row = inflater.inflate(R.layout.item_selling_country, parent, false);

        /***** Get each Model object from Arraylist ********/
        CountryData.CountriesBean bean = data.get(position);

        TextView text_country = (TextView) row.findViewById(R.id.text_country);
        ImageView image_country = (ImageView) row.findViewById(R.id.image_country);

        text_country.setText(bean.name + " (" + bean.code + ")");
        image_country.setImageResource(R.drawable.flags);
        image_country.setScaleType(ImageView.ScaleType.MATRIX);

        Drawable drawable = activity.getResources().getDrawable(R.drawable.flags);

        int height = drawable.getIntrinsicHeight();
        int width = drawable.getIntrinsicWidth();

        int left = (bean.left * width) / 288;
        int top = (bean.top * height) / 266;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            image_country.drawableHotspotChanged(left, top);
        }
        return row;
    }

}
