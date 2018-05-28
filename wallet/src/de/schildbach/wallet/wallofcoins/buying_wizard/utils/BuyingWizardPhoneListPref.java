package de.schildbach.wallet.wallofcoins.buying_wizard.utils;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.buying_wizard.models.BuyingWizardPhoneListVO;


/**
 * Created on 12-Mar-18.
 */

public class BuyingWizardPhoneListPref {

    private final SharedPreferences prefs;
    private static final String PREF_CREDENTIALS_LIST = "PREF_CREDENTIALS_LIST";

    public BuyingWizardPhoneListPref(final SharedPreferences prefs) {
        this.prefs = prefs;
    }


    public void addPhone(String phone, String deviceId) {
        ArrayList<BuyingWizardPhoneListVO> voArrayList;

        try {
            voArrayList = (ArrayList<BuyingWizardPhoneListVO>) BuyingWizardObjectSerializer
                    .deserialize(prefs.getString(PREF_CREDENTIALS_LIST,
                            BuyingWizardObjectSerializer.serialize(new ArrayList())));
            BuyingWizardPhoneListVO createHoldResp = new BuyingWizardPhoneListVO();
            createHoldResp.setDeviceId(deviceId);
            createHoldResp.setPhoneNumber(phone);

            voArrayList.add(createHoldResp);


            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREF_CREDENTIALS_LIST, BuyingWizardObjectSerializer.serialize(voArrayList));

            editor.commit();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public ArrayList<BuyingWizardPhoneListVO> getStoredPhoneList() {
        ArrayList<BuyingWizardPhoneListVO> voArrayList = new ArrayList<>();

        try {
            voArrayList = (ArrayList) BuyingWizardObjectSerializer.deserialize(prefs.getString(PREF_CREDENTIALS_LIST,
                    BuyingWizardObjectSerializer.serialize(new ArrayList())));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return voArrayList;
    }

    public String getDeviceIdFromPhone(String phone) {
        String deviceId = "";
        ArrayList<BuyingWizardPhoneListVO> voArrayList;

        try {
            voArrayList = (ArrayList<BuyingWizardPhoneListVO>) BuyingWizardObjectSerializer
                    .deserialize(prefs.getString(PREF_CREDENTIALS_LIST,
                            BuyingWizardObjectSerializer.serialize(new ArrayList())));

            for (BuyingWizardPhoneListVO vo : voArrayList) {
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
