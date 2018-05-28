package de.schildbach.wallet.wallofcoins.selling_wizard.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.ConfirmDepositResp;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet.wallofcoins.response.DiscoveryInputsResp;
import de.schildbach.wallet.wallofcoins.response.GetAuthTokenResp;
import de.schildbach.wallet.wallofcoins.response.GetCurrencyResp;
import de.schildbach.wallet.wallofcoins.response.GetHoldsResp;
import de.schildbach.wallet.wallofcoins.response.GetOffersResp;
import de.schildbach.wallet.wallofcoins.response.OrderListResp;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardAddressListRespVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardAddressVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardAuthVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardCreateDeviceVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardGetRecOptionsResp;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardMarketsVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardSendVeriRespVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardSignUpRespVo;
import de.schildbach.wallet.wallofcoins.selling_wizard.models.SellingWizardVerifyAdResp;
import retrofit2.Call;
import retrofit2.http.DELETE;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Created on 04-Apr-18.
 */

public interface SellingApi {

    @FormUrlEncoded
    @POST(SellingApiConstants.CREATE_AUTH)
    Call<SellingWizardSignUpRespVo> signUp(@FieldMap Map<String, String> partMap);


    //crypto=DASH   currency=USD
    @GET(SellingApiConstants.MARKETS + "{crypto}/{currency}/")
    Call<ArrayList<SellingWizardMarketsVo>> getMarkets(@Path("crypto") String crypto, @Path("currency") String currency);


    //create address
    @FormUrlEncoded
    @POST(SellingApiConstants.CREATE_ADDRESS)
    Call<SellingWizardAddressVo> createAddress(@FieldMap Map<String, Object> partMap);


    //send verification code
    @FormUrlEncoded
    @POST(SellingApiConstants.SEND_VERIFICATION)
    Call<SellingWizardSendVeriRespVo> sendVerificationCode(@FieldMap Map<String, String> partMap);

    @GET(SellingApiConstants.GET_AUTH + "{phone}/")
    Call<SellingWizardAuthVo> getAuthToken(@Path("phone") String phone, @Query("publisherId") String publisherId);


    @FormUrlEncoded
    @POST("api/v1/auth/{phone}/authorize/")
    Call<SellingWizardAuthVo> authorize(@Path("phone") String username, @FieldMap Map<String, String> partMap);

    @DELETE("api/v1/auth/{phone}/")
    Call<CheckAuthResp> deleteAuth(@Path("phone") String username, @Query("publisherId") String publisherId);

    @FormUrlEncoded
    @POST("api/verifyAd/")
    Call<SellingWizardVerifyAdResp> verifyAd(@FieldMap Map<String, String> partMap);

    @GET("api/v1/ad/")
    Call<ArrayList<SellingWizardAddressListRespVo>> getAddressListing();

    //----------------------------------------------------------
    @GET("api/v1/orders/")
    Call<List<OrderListResp>> getOrders(@Query("publisherId") String publisherId);


    @DELETE("api/v1/orders/{orderId}/")
    Call<Void> cancelOrder(@Path("orderId") String orderId, @Query("publisherId") String publisherId);

    @FormUrlEncoded
    @POST("api/v1/auth/{phone}/authorize/")
    Call<GetAuthTokenResp> getAuthToken(@Path("phone") String username, @FieldMap Map<String, String> partMap);


    //-------------- wizard
    @GET("api/v1/banks/")
    Call<List<SellingWizardGetRecOptionsResp>> getReceivingOptions(@Query("country") String country);
    //----------------------

    @GET("api/v1/currency/")
    Call<List<GetCurrencyResp>> getCurrency();

    @FormUrlEncoded
    @POST("api/v1/discoveryInputs/")
    Call<DiscoveryInputsResp> discoveryInputs(@FieldMap Map<String, String> partMap);

    @GET("api/v1/discoveryInputs/{discoveryId}/offers/")
    Call<GetOffersResp> getOffers(@Path("discoveryId") String discoveryId, @Query("publisherId") String publisherId);

    @FormUrlEncoded
    @POST("api/v1/holds/")
    Call<CreateHoldResp> createHold(@FieldMap Map<String, String> partMap);

    @GET("api/v1/holds/")
    Call<List<GetHoldsResp>> getHolds();

    @DELETE("api/v1/holds/{id}/")
    Call<Void> deleteHold(@Path("id") String id);

    @FormUrlEncoded
    @POST("api/v1/holds/{id}/capture/")
    Call<List<CaptureHoldResp>> captureHold(@Path("id") String id, @FieldMap Map<String, String> partMap);

    @FormUrlEncoded
    @POST("api/v1/orders/{holdId}/confirmDeposit/")
    Call<ConfirmDepositResp> confirmDeposit(@Path("holdId") String holdId, @Field("your_field") String yourField, @Query("publisherId") String publisherId);

    @FormUrlEncoded
    @POST("api/v1/devices/")
    Call<SellingWizardCreateDeviceVo> createDevice(@FieldMap Map<String, String> partMap);

    @GET("api/v1/devices/")
    Call<List<SellingWizardCreateDeviceVo>> getDevice();


}
