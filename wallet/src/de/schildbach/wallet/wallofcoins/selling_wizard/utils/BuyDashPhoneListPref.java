package de.schildbach.wallet.wallofcoins.selling_wizard.utils;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.selling_wizard.models.PhoneListVO;


/**
 * Created by  on 12-Mar-18.
 */

public class BuyDashPhoneListPref {

    private final SharedPreferences prefs;
    private static final String CREDENTIALS_LIST = "credentials_list";

    public BuyDashPhoneListPref(final SharedPreferences prefs) {
        this.prefs = prefs;
    }


    public void addPhone(String phone, String deviceId) {
        ArrayList<PhoneListVO> voArrayList;

        try {
            voArrayList = (ArrayList<PhoneListVO>) ObjectSerializer.deserialize(prefs.getString(CREDENTIALS_LIST,
                    ObjectSerializer.serialize(new ArrayList())));
            PhoneListVO createHoldResp = new PhoneListVO();
            createHoldResp.setDeviceId(deviceId);
            createHoldResp.setPhoneNumber(phone);

            voArrayList.add(createHoldResp);


            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(CREDENTIALS_LIST, ObjectSerializer.serialize(voArrayList));

            editor.commit();

            for (PhoneListVO vo : voArrayList) {
                Log.e("Auth id list", vo.getDeviceId());
                Log.e("phone no list", vo.getPhoneNumber());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public ArrayList<PhoneListVO> getStoredPhoneList() {
        ArrayList<PhoneListVO> voArrayList = new ArrayList<>();

        try {
            voArrayList = (ArrayList) ObjectSerializer.deserialize(prefs.getString(CREDENTIALS_LIST,
                    ObjectSerializer.serialize(new ArrayList())));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return voArrayList;
    }

    public String getDeviceIdFromPhone(String phone) {
        String deviceId = "";
        ArrayList<PhoneListVO> voArrayList;

        try {
            voArrayList = (ArrayList<PhoneListVO>) ObjectSerializer.deserialize(prefs.getString(CREDENTIALS_LIST,
                    ObjectSerializer.serialize(new ArrayList())));

            for (PhoneListVO vo : voArrayList) {
                Log.e("Stored phone",vo.getPhoneNumber()+"---"+"Stored deviceId"+vo.getDeviceId());
                if (vo.getPhoneNumber().equalsIgnoreCase(phone)) {
                    deviceId = vo.getDeviceId();
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return deviceId;
    }
}
