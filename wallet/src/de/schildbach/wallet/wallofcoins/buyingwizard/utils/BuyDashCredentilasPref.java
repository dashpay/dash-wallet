package de.schildbach.wallet.wallofcoins.buyingwizard.utils;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.buyingwizard.models.CredentialsVO;

/**
 * Created by  on 12-Mar-18.
 */

public class BuyDashCredentilasPref {

    private final SharedPreferences prefs;
    private static final String CREDENTIALS_LIST = "credentials_list";

    public BuyDashCredentilasPref(final SharedPreferences prefs) {
        this.prefs = prefs;
    }


    public void setCredentials(String phone, String deviceToken) {
        ArrayList<CredentialsVO> voArrayList;

        try {
            voArrayList = (ArrayList) ObjectSerializer.deserialize(prefs.getString(CREDENTIALS_LIST,
                    ObjectSerializer.serialize(new ArrayList())));
            CredentialsVO createHoldResp = new CredentialsVO();
            createHoldResp.setDeviceToken(deviceToken);
            createHoldResp.setPhoneNumber(phone);

            voArrayList.add(createHoldResp);


            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(CREDENTIALS_LIST, ObjectSerializer.serialize(voArrayList));

            editor.commit();

            for (CredentialsVO vo : voArrayList) {
                Log.e("Device id list", vo.getDeviceToken());
                Log.e("phone no list", vo.getPhoneNumber());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public ArrayList<CredentialsVO> getCredentialsList() {
        ArrayList<CredentialsVO> voArrayList = new ArrayList<>();

        try {
            voArrayList = (ArrayList) ObjectSerializer.deserialize(prefs.getString(CREDENTIALS_LIST,
                    ObjectSerializer.serialize(new ArrayList())));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return voArrayList;
    }

    public String getDeviceIdFromPhoneNum(String phone) {
        String deviceId = "";
        ArrayList<CredentialsVO> voArrayList;

        try {
            voArrayList = (ArrayList) ObjectSerializer.deserialize(prefs.getString(CREDENTIALS_LIST,
                    ObjectSerializer.serialize(new ArrayList())));

            for (CredentialsVO vo : voArrayList) {
                if (vo.getPhoneNumber().equals(phone)) {
                    deviceId = vo.getDeviceToken();
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return deviceId;
    }
}
