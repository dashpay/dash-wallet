package org.dash.wallet.integration.uphold.data;


import android.content.Context;

import org.dash.wallet.integration.uphold.R;
import org.json.JSONException;
import org.json.JSONObject;

import retrofit2.Response;

public class UpholdApiException extends Exception {

    public static final String IDENTITY_ERROR_KEY = "identity";
    public static final String TOKEN_ERROR_KEY = "token";
    public static final String LOCKED_FUNDS_KEY = "sufficient_unlocked_funds";
    public static final String AVAILABLE_AT_KEY = "availableAt";

    private int httpStatusCode;

    //{
    //    "code":"sufficient_unlocked_funds",
    //    "message":"You will have sufficient funds by 2020-04-17T22:42:00.151Z"
    //    "args":{
    //        "availableAt":"2020-04-17T22:42:00.151Z",
    //        "missing":"1.03430005",
    //        "currency":"DASH"
    //    },
    //}
    private JSONObject errorBody;

    public UpholdApiException(Response response) {
        this.httpStatusCode = response.code();
        if (response.errorBody() != null) {
            try {
                errorBody = new JSONObject(response.errorBody().string());
            } catch (Exception e) {
                errorBody = null;
            }
        }
    }

    public UpholdApiException(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public String getErrorCode() {
        if (errorBody != null) {
            try {
                return errorBody.getString("code");
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    public JSONObject getErrors() {
        if (errorBody != null) {
            try {
                return errorBody.getJSONObject("errors");
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    private JSONObject getError(String errorKey) {
        JSONObject errors = getErrors();
        if (errors != null) {
            try {
                return errors.getJSONObject(errorKey);
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    private String getErrorField(String errorKey, String errorField) {
        JSONObject error = getError(errorKey);
        if (error != null) {
            try {
                return error.getString(errorField);
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    public String getErrorCode(String errorKey) {
        return getErrorField(errorKey, "code");
    }

    public String getErrorMessage(String errorKey) {
        return getErrorField(errorKey, "message");
    }

    public boolean hasError(String errorField) {
        JSONObject errors = getErrors();
        return (errors != null) && errors.has(errorField);
    }

    public boolean isTokenError() {
        JSONObject errors = getErrors();
        return (errors != null) && errors.has(TOKEN_ERROR_KEY);
    }

    public boolean isIdentityError() {
        JSONObject errors = getErrors();
        return (errors != null) && errors.has(IDENTITY_ERROR_KEY);
    }

    private JSONObject getErrorArgs() {
        if (errorBody != null) {
            try {
                return errorBody.getJSONObject("args");
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    public String getErrorArg(String errorArgKey) {
        JSONObject errorArgs = getErrorArgs();
        if (errorArgs != null) {
            try {
                return errorArgs.getString(errorArgKey);
            } catch (JSONException e) {
                // swallow
            }
        }
        return null;
    }

    public int getCode() {
        return httpStatusCode;
    }

    public String getDescription(Context context, Object... formatArgs) {
        if (isIdentityError()) {
            return context.getString(R.string.uphold_api_error_identity);
        } else if (httpStatusCode == 400) {
            return context.getString(R.string.uphold_api_error_400_description, formatArgs);
        } else if (httpStatusCode == 403) {
            return context.getString(R.string.uphold_api_error_403_description);
        } else {
//            return getGenericDescription();
            return context.getString(R.string.loading_error);
        }
    }

    // based on https://uphold.com/en/developer/api/documentation/#errors
    private String getGenericDescription() {
        switch (httpStatusCode) {
            case 400:
                return "Bad Request – Validation failed";
            case 401:
                return "Unauthorized – Bad credentials";
            case 403:
                return "Forbidden – Access forbidden";
            case 404:
                return "Not Found – Object not found";
            case 409:
                return "Conflict – Entity already exists";
            case 412:
                return "Precondition Failed";
            case 416:
                return "Requested Range Not Satisfiable";
            case 429:
                return "Too Many Requests – Rate limit exceeded";
            default:
                return "Unknown Error";
        }
    }
}
