package org.dash.wallet.integration.liquid.data

import com.google.gson.annotations.SerializedName

data class LiquidSessionId(

	@field:SerializedName("environment")
	val environment: String? = null,

	@field:SerializedName("payload")
	val payload: Payload? = null,

	@field:SerializedName("success")
	val success: Boolean? = null,

	@field:SerializedName("message")
	val message: String? = null
)

data class ClientInfo(

	@field:SerializedName("ip_country")
	val ipCountry: String? = null
)

data class FundingSettlement(

	@field:SerializedName("quantity")
	val quantity: String? = null,

	@field:SerializedName("currency")
	val currency: String? = null
)

data class TermsOfService(

	@field:SerializedName("href")
	val href: String? = null
)

data class ProofOfAddress(

	@field:SerializedName("approved")
	val approved: Boolean? = null,

	@field:SerializedName("submitted")
	val submitted: Boolean? = null,

	@field:SerializedName("deferrable")
	val deferrable: Any? = null,

	@field:SerializedName("reviewed")
	val reviewed: Boolean? = null,

	@field:SerializedName("required")
	val required: Boolean? = null
)

data class DefaultTransaction(

	@field:SerializedName("funding_settlement")
	val fundingSettlement: FundingSettlement? = null,

	@field:SerializedName("payout_settlement")
	val payoutSettlement: PayoutSettlement? = null
)

data class Identification(

	@field:SerializedName("approved")
	val approved: Boolean? = null,

	@field:SerializedName("submitted")
	val submitted: Boolean? = null,

	@field:SerializedName("deferrable")
	val deferrable: Any? = null,

	@field:SerializedName("reviewed")
	val reviewed: Boolean? = null,

	@field:SerializedName("required")
	val required: Boolean? = null
)

data class Links(

	@field:SerializedName("terms_of_service")
	val termsOfService: TermsOfService? = null
)

data class PayoutSettlement(

	@field:SerializedName("currency")
	val currency: String? = null
)

data class Liveness(

	@field:SerializedName("approved")
	val approved: Boolean? = null,

	@field:SerializedName("submitted")
	val submitted: Boolean? = null,

	@field:SerializedName("deferrable")
	val deferrable: Any? = null,

	@field:SerializedName("reviewed")
	val reviewed: Boolean? = null,

	@field:SerializedName("required")
	val required: Boolean? = null
)

data class Payload(

	@field:SerializedName("environment")
	val environment: String? = null,

	@field:SerializedName("client_info")
	val clientInfo: ClientInfo? = null,

	@field:SerializedName("default_transaction")
	val defaultTransaction: DefaultTransaction? = null,

	@field:SerializedName("_links")
	val links: Links? = null,

	@field:SerializedName("public_api_key")
	val publicApiKey: String? = null,

	@field:SerializedName("session_id")
	val sessionId: String? = null,

	@field:SerializedName("session_secret")
	val sessionSecret: String? = null,

	@field:SerializedName("kyc_state")
	val kycState: KycState? = null
)

data class KycState(

	@field:SerializedName("identification")
	val identification: Identification? = null,

	@field:SerializedName("contact")
	val contact: Contact? = null,

	@field:SerializedName("liveness")
	val liveness: Liveness? = null,

	@field:SerializedName("proof_of_address")
	val proofOfAddress: ProofOfAddress? = null
)
