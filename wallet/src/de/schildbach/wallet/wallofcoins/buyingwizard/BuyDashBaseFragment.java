package de.schildbach.wallet.wallofcoins.buyingwizard;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import java.util.List;
import java.util.Locale;

import de.schildbach.wallet.AddressBookProvider;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet_test.R;

/**
 * Created on 6/3/18.
 */

public class BuyDashBaseFragment extends Fragment {

    private final int PERMISSIONS_REQUEST_LOCATION = 8989;
    protected Context mContext;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }


    protected boolean checkPermissions() {
        int permissionFineLoc = ActivityCompat.checkSelfPermission(mContext,
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        int permissionCoarseLoc = ActivityCompat.checkSelfPermission(mContext,
                android.Manifest.permission.ACCESS_COARSE_LOCATION);

        return permissionFineLoc == PackageManager.PERMISSION_GRANTED &&
                permissionCoarseLoc == PackageManager.PERMISSION_GRANTED;
    }

    protected void requestLocationPermission() {
        requestPermissions(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION
                        , Manifest.permission.ACCESS_COARSE_LOCATION},
                PERMISSIONS_REQUEST_LOCATION);
    }

    /**
     * Get Last known best accurate location from available providers
     *
     * @return location object
     */
    protected Location getLastKnownLocation() {
        boolean gps_enabled = false;
        boolean network_enabled = false;
        LocationManager lm = (LocationManager) getActivity()
                .getSystemService(Context.LOCATION_SERVICE);
        gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        Location net_loc = null, gps_loc = null, finalLoc = null;
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (gps_enabled)
                gps_loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (network_enabled)
                net_loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        } else {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERMISSIONS_REQUEST_LOCATION);
        }

        if (gps_loc != null && net_loc != null) {
            if (gps_loc.getAccuracy() > net_loc.getAccuracy())
                finalLoc = net_loc;
            else
                finalLoc = gps_loc;
        } else {

            if (gps_loc != null) {
                finalLoc = gps_loc;
            } else if (net_loc != null) {
                finalLoc = net_loc;
            }
        }
        return finalLoc;
    }
    /**
     * Method for Open web url link in external browser app
     *
     * @param url
     */
    protected void goToUrl(String url) {
        Uri uriUrl = Uri.parse(url);
        Intent launchBrowser = new Intent(Intent.ACTION_VIEW, uriUrl);
        startActivity(launchBrowser);
    }
    /**
     * get country code form current latitude & longitude
     *
     * @return Country Code
     */
    protected String getCountryCode(double latitude,double longitude) {
        String countryCode = "";
        try {
            Geocoder geo = new Geocoder(mContext, Locale.getDefault());
            List<Address> addresses = geo.getFromLocation(latitude, longitude, 1);
            android.location.Address obj = addresses.get(0);
            countryCode = obj.getCountryCode();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (countryCode.equals("")) {
            return "us";
        } else {
            return countryCode;
        }
    }
    /**
     * Method for hide keyboard
     */
    protected void hideKeyBoard() {
        View view = getActivity().getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
    /**
     * Show a dialog to the user requesting that GPS be enabled
     */
    protected void showDialogGPS() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setCancelable(false);
        builder.setTitle(getString(R.string.enable_gps));
        builder.setMessage(getString(R.string.enable_gps_location));
        builder.setPositiveButton(getString(R.string.enable_label), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                startActivity(
                        new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            }
        });
        builder.setNegativeButton(getString(R.string.ignore_label), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    /**
     * Method for Update Address book of Order Transaction
     *
     * @param KEY_ADDRESS
     * @param newLabel
     */
    protected void updateAddressBookValue(String KEY_ADDRESS, String newLabel) {
        if (KEY_ADDRESS != null && newLabel != null) {
            org.bitcoinj.core.Address keyAddress = org.bitcoinj.core.Address.fromBase58(Constants.NETWORK_PARAMETERS, KEY_ADDRESS);
            final Uri uri = AddressBookProvider.contentUri(mContext.getPackageName()).buildUpon().appendPath(keyAddress.toBase58()).build();
            final String addressLabel = AddressBookProvider.resolveLabel(mContext, keyAddress.toBase58());

            ContentResolver contentResolver;
            contentResolver = mContext.getContentResolver();

            final ContentValues values = new ContentValues();

            values.put(AddressBookProvider.KEY_LABEL, newLabel);

            if (addressLabel == null) {
                contentResolver.insert(uri, values);
            } else {
                contentResolver.update(uri, values, null, null);
            }
        }
    }
}

