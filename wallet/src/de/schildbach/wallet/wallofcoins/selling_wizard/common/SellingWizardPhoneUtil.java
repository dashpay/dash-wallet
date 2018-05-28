package de.schildbach.wallet.wallofcoins.selling_wizard.common;

import android.util.Log;

import java.util.ArrayList;

import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardPhoneListVO;
import de.schildbach.wallet.wallofcoins.selling_wizard.storage.SharedPreferenceUtil;
import de.schildbach.wallet.wallofcoins.selling_wizard.utils.ObjectSerializer;

/**
 * Created on 04-Apr-18.
 */

public class SellingWizardPhoneUtil {

    private static final String PREF_SELLING_SESSION_DETAILS = "PREF_SELLING_SESSION_DETAILS";

    public static void addPhone(String phone, String deviceId) {
        ArrayList<SellingWizardPhoneListVO> voArrayList;

        try {
            voArrayList = (ArrayList<SellingWizardPhoneListVO>) ObjectSerializer.deserialize(
                    SharedPreferenceUtil.getString(PREF_SELLING_SESSION_DETAILS,
                            ObjectSerializer.serialize(new ArrayList())));
            SellingWizardPhoneListVO createHoldResp = new SellingWizardPhoneListVO();
            createHoldResp.setDeviceId(deviceId);
            createHoldResp.setPhoneNumber(phone);

            voArrayList.add(createHoldResp);


            SharedPreferenceUtil.putValue(PREF_SELLING_SESSION_DETAILS,
                    ObjectSerializer.serialize(voArrayList));


            for (SellingWizardPhoneListVO vo : voArrayList) {
                Log.e("Auth id list", vo.getDeviceId());
                Log.e("phone no list", vo.getPhoneNumber());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static ArrayList<SellingWizardPhoneListVO> getStoredPhoneList() {
        ArrayList<SellingWizardPhoneListVO> voArrayList = new ArrayList<>();

        try {
            voArrayList = (ArrayList) ObjectSerializer.deserialize(
                    SharedPreferenceUtil.getString(PREF_SELLING_SESSION_DETAILS,
                            ObjectSerializer.serialize(new ArrayList())));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return voArrayList;
    }

    public String getDeviceIdFromPhone(String phone) {
        String deviceId = "";
        ArrayList<SellingWizardPhoneListVO> voArrayList;

        try {
            voArrayList = (ArrayList<SellingWizardPhoneListVO>) ObjectSerializer.
                    deserialize(SharedPreferenceUtil.getString(PREF_SELLING_SESSION_DETAILS,
                            ObjectSerializer.serialize(new ArrayList())));

            for (SellingWizardPhoneListVO vo : voArrayList) {
                Log.e("Stored phone", vo.getPhoneNumber() + "---" + "Stored deviceId" + vo.getDeviceId());
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
