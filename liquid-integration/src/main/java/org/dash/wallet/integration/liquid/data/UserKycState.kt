package org.dash.wallet.integration.liquid.data

import com.google.gson.annotations.SerializedName

data class UserKycState(

	@field:SerializedName("environment")
	val environment: String? = null,

	@field:SerializedName("payload")
	val payload: UserKycStatePayload? = null,

	@field:SerializedName("success")
	val success: Boolean? = null,

	@field:SerializedName("message")
	val message: String? = null
)

data class Deferrable(

	@field:SerializedName("person")
	val person: Person? = null,

	@field:SerializedName("overall")
	val overall: Boolean? = null,

	@field:SerializedName("registration")
	val registration: Registration? = null,

	@field:SerializedName("transaction")
	val transaction: Transaction? = null
)

data class Registration(

	@field:SerializedName("eligible")
	val eligible: Boolean? = null
)

data class UserKycStatePayload(

	@field:SerializedName("reputation_id")
	val reputationId: String? = null,

	@field:SerializedName("metadata")
	val metadata: Metadata? = null,

	@field:SerializedName("identification")
	val identification: Identification? = null,

	@field:SerializedName("kyc_proof_id")
	val kycProofId: String? = null,

	@field:SerializedName("contact")
	val contact: Contact? = null,

	@field:SerializedName("kyc_identity_id")
	val kycIdentityId: String? = null,

	@field:SerializedName("liveness")
	val liveness: Liveness? = null,

	@field:SerializedName("proof_of_address")
	val proofOfAddress: ProofOfAddress? = null,

	@field:SerializedName("liquid_user_id")
	val liquidUserId: String? = null
)

data class Inputs(

	@field:SerializedName("valueOfPrevious30days")
	val valueOfPrevious30days: ValueOfPrevious30days? = null,

	@field:SerializedName("countries")
	val countries: List<CountriesItem?>? = null
)

data class Metadata(

	@field:SerializedName("inputs")
	val inputs: Inputs? = null
)


data class ValueOfPrevious30days(

	@field:SerializedName("usd_equivalent")
	val usdEquivalent: Any? = null,

	@field:SerializedName("eth_equivalent")
	val ethEquivalent: Any? = null,

	@field:SerializedName("btc_equivalent")
	val btcEquivalent: Any? = null,

	@field:SerializedName("sgd_equivalent")
	val sgdEquivalent: Any? = null,

	@field:SerializedName("jpy_equivalent")
	val jpyEquivalent: Any? = null,

	@field:SerializedName("eur_equivalent")
	val eurEquivalent: Any? = null
)

data class CountriesItem(

	@field:SerializedName("country")
	val country: String? = null,

	@field:SerializedName("source")
	val source: String? = null
)

data class Transaction(

	@field:SerializedName("eligible")
	val eligible: Boolean? = null
)

data class Contact(

	@field:SerializedName("approved")
	val approved: Boolean? = null,

	@field:SerializedName("submitted")
	val submitted: Boolean? = null,

	@field:SerializedName("deferrable")
	val deferrable: Boolean? = null,
//	val deferrable: Deferrable? = null,

	@field:SerializedName("reviewed")
	val reviewed: Boolean? = null,

	@field:SerializedName("required")
	val required: Boolean? = null
)

data class Person(

	@field:SerializedName("eligible")
	val eligible: Boolean? = null
)
