package org.dash.android.lightpayprot

import okhttp3.Interceptor
import okhttp3.Response
import java.security.cert.X509Certificate
import java.util.regex.Matcher
import java.util.regex.Pattern

class SupportInterceptor : Interceptor {

    companion object {
        const val EXT_HEADER_PAYEE_NAME = "PayeeName"
        const val EXT_HEADER_PAYEE_VERIFIED_BY = "PayeeVerifiedBy"
    }

    private val regex = "(?:^|,\\s?)(?:(?<name>[A-Z]+)=(?<val>\"(?:[^\"]|\"\")+\"|[^,]+))+"
    private val pattern: Pattern = Pattern.compile(regex)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val peerCertificates = response.handshake?.peerCertificates
        if (!peerCertificates.isNullOrEmpty()) {
            val certificate = peerCertificates.last()
            if (certificate is X509Certificate) {
                val certIssuerName = extractIssuerName(certificate)
                return response.newBuilder()
                        .addHeader(EXT_HEADER_PAYEE_NAME, request.url.host)
                        .addHeader(EXT_HEADER_PAYEE_VERIFIED_BY, certIssuerName)
                        .build()
            }
        }
        return response
    }

    /**
     * from certificate issuer name from
     * eg. CN=DST Root CA X3,O=Digital Signature Trust Co. => Digital Signature Trust Co.
     *
     * https://stackoverflow.com/a/49627753/795721
     */
    private fun extractIssuerName(certificate: X509Certificate): String {
        val matcher: Matcher = pattern.matcher(certificate.issuerX500Principal.name)
        while (matcher.find()) {
            if (matcher.groupCount() == 2) {
                if (matcher.group(1) == "O") {
                    return matcher.group(2)!!
                }
            }
        }
        return ""
    }
}