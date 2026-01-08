package com.didrikquant.kraken

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

public object KrakenAuth {

    public fun signChallenge(challenge: String, apiSecret: String): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val challengeHash = sha256.digest(challenge.toByteArray(Charsets.UTF_8))

        val decodedSecret = Base64.getDecoder().decode(apiSecret)
        val hmacKey = SecretKeySpec(decodedSecret, "HmacSHA512")
        val hmac = Mac.getInstance("HmacSHA512")
        hmac.init(hmacKey)

        val hmacDigest = hmac.doFinal(challengeHash)
        return Base64.getEncoder().encodeToString(hmacDigest)
    }

    public fun signRequest(postData: String, apiSecret: String, endpointPath: String): String {
        val dataToSign = postData + endpointPath
        val sha256 = MessageDigest.getInstance("SHA-256")
        val hash = sha256.digest(dataToSign.toByteArray(Charsets.UTF_8))

        val decodedSecret = Base64.getDecoder().decode(apiSecret)
        val hmacKey = SecretKeySpec(decodedSecret, "HmacSHA512")
        val hmac = Mac.getInstance("HmacSHA512")
        hmac.init(hmacKey)

        val hmacDigest = hmac.doFinal(hash)
        return Base64.getEncoder().encodeToString(hmacDigest)
    }

    public fun generateNonce(): String = System.currentTimeMillis().toString()
}
