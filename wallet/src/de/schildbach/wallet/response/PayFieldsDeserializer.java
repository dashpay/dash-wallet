package de.schildbach.wallet.response;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

import de.schildbach.wallet.response.GetReceivingOptionsResp.PayFieldsBeanX;
import de.schildbach.wallet.response.GetReceivingOptionsResp.JsonPayFieldsBeanX;

public class PayFieldsDeserializer implements JsonDeserializer<PayFieldsBeanX> {

    private static final String TAG = PayFieldsDeserializer.class.getSimpleName();

    @Override
    public PayFieldsBeanX deserialize(JsonElement jsonElement, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (jsonElement.isJsonPrimitive()) {
            PayFieldsBeanX payFieldsBeanX = new PayFieldsBeanX();
            payFieldsBeanX.payFieldsB = jsonElement.getAsBoolean();
            return payFieldsBeanX;
        }

        return context.deserialize(jsonElement, JsonPayFieldsBeanX.class);
    }

//    @Override
//    public void write(JsonWriter jsonWriter, Object value) throws IOException {
//
//        jsonWriter.beginObject();
//
//        if (value instanceof Boolean) {
//            jsonWriter.value((Boolean) value);
//        } else if (value instanceof PayFieldsBeanX) {
//            new Gson().toJson(value, PayFieldsBeanX.class, jsonWriter);
//        }
//
//        jsonWriter.endObject();
//        jsonWriter.close();
//    }
//
//    @Override
//    public Object read(JsonReader jsonReader) throws IOException {
//        PayFieldsBeanX payFieldsBeanX = null;
//        Boolean isBool = false;
//        Boolean flag = false;
//        try {
//            jsonReader.beginObject();
//            payFieldsBeanX = new Gson().fromJson(jsonReader, PayFieldsBeanX.class);
//            jsonReader.endObject();
//            jsonReader.close();
//        } catch (Exception e) {
//            isBool = true;
//            flag = new Gson().fromJson(jsonReader, Boolean.class);
//        }
//
//
//        return isBool ? flag : payFieldsBeanX;
//    }
}
