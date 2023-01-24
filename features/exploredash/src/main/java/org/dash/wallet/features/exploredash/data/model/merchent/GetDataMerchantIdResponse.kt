package org.dash.wallet.features.exploredash.data.model.merchent

import com.google.gson.annotations.SerializedName

data class GetDataMerchantIdResponse(
    @SerializedName("AccessToken")
    val accessToken: String? = "",
    @SerializedName("Data")
    val `data`: Data? = Data(),
    @SerializedName("DelayedToken")
    val delayedToken: String? = "",
    @SerializedName("ErrorMessage")
    val errorMessage: String? = "",
    @SerializedName("IsDelayed")
    val isDelayed: Boolean? = false,
    @SerializedName("Successful")
    val successful: Boolean? = false
) {
    data class Data(
        @SerializedName("Images")
        val images: Images? = Images(),
        @SerializedName("Locations")
        val locations: List<Location?>? = listOf(),
        @SerializedName("Merchant")
        val merchant: Merchant? = Merchant()
    ) {
        data class Images(
            val additionalProp1: String? = "",
            val additionalProp2: String? = "",
            val additionalProp3: String? = ""
        )

        data class Location(
            @SerializedName("Address1")
            val address1: String? = "",
            @SerializedName("Address2")
            val address2: String? = "",
            @SerializedName("BusinessDescription")
            val businessDescription: String? = "",
            @SerializedName("City")
            val city: String? = "",
            @SerializedName("CountryId")
            val countryId: Int? = 0,
            @SerializedName("CreatedBy")
            val createdBy: Int? = 0,
            @SerializedName("CreatedDate")
            val createdDate: String? = "",
            @SerializedName("Distance")
            val distance: Int? = 0,
            @SerializedName("Email")
            val email: String? = "",
            @SerializedName("Fax")
            val fax: String? = "",
            @SerializedName("FridayClose")
            val fridayClose: FridayClose? = FridayClose(),
            @SerializedName("FridayOpen")
            val fridayOpen: FridayOpen? = FridayOpen(),
            @SerializedName("GpsLat")
            val gpsLat: Int? = 0,
            @SerializedName("GpsLong")
            val gpsLong: Int? = 0,
            @SerializedName("HasInventory")
            val hasInventory: Boolean? = false,
            @SerializedName("Id")
            val id: Int? = 0,
            @SerializedName("IsActive")
            val isActive: Boolean? = false,
            @SerializedName("IsDeleted")
            val isDeleted: Boolean? = false,
            @SerializedName("IsPhysicalLocation")
            val isPhysicalLocation: Boolean? = false,
            @SerializedName("LogoUrl")
            val logoUrl: String? = "",
            @SerializedName("MerchantId")
            val merchantId: Int? = 0,
            @SerializedName("ModifiedBy")
            val modifiedBy: Int? = 0,
            @SerializedName("ModifiedDate")
            val modifiedDate: String? = "",
            @SerializedName("MondayClose")
            val mondayClose: MondayClose? = MondayClose(),
            @SerializedName("MondayOpen")
            val mondayOpen: MondayOpen? = MondayOpen(),
            @SerializedName("Name")
            val name: String? = "",
            @SerializedName("OverrideBusinessDescription")
            val overrideBusinessDescription: Boolean? = false,
            @SerializedName("Phone")
            val phone: String? = "",
            @SerializedName("PostalCode")
            val postalCode: String? = "",
            @SerializedName("SaturdayClose")
            val saturdayClose: SaturdayClose? = SaturdayClose(),
            @SerializedName("SaturdayOpen")
            val saturdayOpen: SaturdayOpen? = SaturdayOpen(),
            @SerializedName("State")
            val state: String? = "",
            @SerializedName("SundayClose")
            val sundayClose: SundayClose? = SundayClose(),
            @SerializedName("SundayOpen")
            val sundayOpen: SundayOpen? = SundayOpen(),
            @SerializedName("ThursdayClose")
            val thursdayClose: ThursdayClose? = ThursdayClose(),
            @SerializedName("ThursdayOpen")
            val thursdayOpen: ThursdayOpen? = ThursdayOpen(),
            @SerializedName("TuesdayClose")
            val tuesdayClose: TuesdayClose? = TuesdayClose(),
            @SerializedName("TuesdayOpen")
            val tuesdayOpen: TuesdayOpen? = TuesdayOpen(),
            @SerializedName("Website")
            val website: String? = "",
            @SerializedName("WednesdayClose")
            val wednesdayClose: WednesdayClose? = WednesdayClose(),
            @SerializedName("WednesdayOpen")
            val wednesdayOpen: WednesdayOpen? = WednesdayOpen()
        ) {
            data class FridayClose(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class FridayOpen(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class MondayClose(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class MondayOpen(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class SaturdayClose(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class SaturdayOpen(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class SundayClose(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class SundayOpen(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class ThursdayClose(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class ThursdayOpen(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class TuesdayClose(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class TuesdayOpen(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class WednesdayClose(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )

            data class WednesdayOpen(
                @SerializedName("Days")
                val days: Int? = 0,
                @SerializedName("Hours")
                val hours: Int? = 0,
                @SerializedName("Milliseconds")
                val milliseconds: Int? = 0,
                @SerializedName("Minutes")
                val minutes: Int? = 0,
                @SerializedName("Seconds")
                val seconds: Int? = 0,
                @SerializedName("Ticks")
                val ticks: Int? = 0,
                @SerializedName("TotalDays")
                val totalDays: Int? = 0,
                @SerializedName("TotalHours")
                val totalHours: Int? = 0,
                @SerializedName("TotalMilliseconds")
                val totalMilliseconds: Int? = 0,
                @SerializedName("TotalMinutes")
                val totalMinutes: Int? = 0,
                @SerializedName("TotalSeconds")
                val totalSeconds: Int? = 0
            )
        }

        data class Merchant(
            @SerializedName("AcceptsTips")
            val acceptsTips: Boolean? = false,
            @SerializedName("CardImageUrl")
            val cardImageUrl: String? = "",
            @SerializedName("CardholderAgreement")
            val cardholderAgreement: String? = "",
            @SerializedName("Description")
            val description: String? = "",
            @SerializedName("HasBarcode")
            val hasBarcode: Boolean? = false,
            @SerializedName("Id")
            val id: Int? = 0,
            @SerializedName("IsOnline")
            val isOnline: Boolean? = false,
            @SerializedName("IsPhysical")
            val isPhysical: Boolean? = false,
            @SerializedName("IsVariablePurchase")
            val isVariablePurchase: Boolean? = false,
            @SerializedName("LegalName")
            val legalName: String? = "",
            @SerializedName("LogoUrl")
            val logoUrl: String? = "",
            @SerializedName("MaximumCardPurchase")
            val maximumCardPurchase: Int? = 0,
            @SerializedName("MinimumCardPurchase")
            val minimumCardPurchase: Int? = 0,
            @SerializedName("PaymentInstructions")
            val paymentInstructions: String? = "",
            @SerializedName("SavingsPercentage")
            val savingsPercentage: Int? = 0,
            @SerializedName("SystemName")
            val systemName: String? = "",
            @SerializedName("TermsAndConditions")
            val termsAndConditions: String? = "",
            @SerializedName("Website")
            val website: String? = ""
        )
    }
}
