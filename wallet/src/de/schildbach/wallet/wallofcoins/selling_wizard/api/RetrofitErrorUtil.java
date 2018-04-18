package de.schildbach.wallet.wallofcoins.selling_wizard.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import de.schildbach.wallet.wallofcoins.selling_wizard.utils.WOCLogUtil;
import retrofit2.Response;

/**
 * Created by  on 05-Apr-18.
 */

public class RetrofitErrorUtil {
    public static String parseError(Response<?> response) {
        BufferedReader reader = null;
        StringBuilder sb = new StringBuilder();
        try {
            reader = new BufferedReader(new InputStreamReader(response.errorBody().byteStream()));
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        String finallyError = sb.toString();

        WOCLogUtil.showLogError("getErrorList", finallyError);
        return finallyError;
    }
}

