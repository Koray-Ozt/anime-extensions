package eu.kanade.tachiyomi.animeextension.tr.animpow

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

class AnimpowApiClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val sourceHeaders: Headers,
) {

    private var publicKey: RSAPublicKey? = null
    private var sessionId: String? = null
    private var aesKey: SecretKey? = null

    suspend fun get(path: String, vararg queryParameters: Pair<String, String?>): JsonObject {
        ensureSession()
        val responseText = secureGet(path, queryParameters)
        val envelope = responseText.asJsonObject()

        if (envelope.string("error") == "Invalid or expired session key" ||
            envelope.string("error") == "Encryption is required for this API route" ||
            envelope.string("error") == "Encryption is required for this API route."
        ) {
            resetSession()
            ensureSession()
            return decryptEnvelope(secureGet(path, queryParameters).asJsonObject())
        }

        return decryptEnvelope(envelope)
    }

    private suspend fun secureGet(path: String, queryParameters: Array<out Pair<String, String?>>): String {
        val requestUrl = "$API_BASE$path".toHttpUrl().newBuilder().apply {
            queryParameters.forEach { (key, value) ->
                if (value != null) addQueryParameter(key, value)
            }
        }.build()

        val request = GET(
            requestUrl,
            sourceHeaders.newBuilder()
                .set("Accept", "application/json, text/plain, */*")
                .set("Content-Type", "application/json")
                .set("X-Session-Id", sessionId ?: "")
                .build(),
        )

        return client.newCall(request).awaitSuccess().use { it.body.string() }
    }

    private suspend fun ensureSession() {
        if (sessionId != null && aesKey != null) return

        val key = publicKey ?: fetchPublicKey().also { publicKey = it }
        val nextAesKey = randomAesKey()
        val nextSessionId = UUID.randomUUID().toString()
        val encryptedKey = encryptAesKey(key, nextAesKey)

        val body = """{"sessionId":"$nextSessionId","encryptedKey":"$encryptedKey"}""".toJsonRequestBody()
        val request = POST(
            "$API_BASE/auth/handshake",
            sourceHeaders.newBuilder()
                .set("Accept", "application/json, text/plain, */*")
                .set("Content-Type", "application/json")
                .build(),
            body = body,
        )

        client.newCall(request).awaitSuccess().close()

        aesKey = nextAesKey
        sessionId = nextSessionId
    }

    private suspend fun fetchPublicKey(): RSAPublicKey {
        val response = client.newCall(GET("$API_BASE/auth/public-key", sourceHeaders)).awaitSuccess()
        val keyPem = response.use {
            it.body.string().asJsonObject().string("publicKey")
                ?: throw Exception("Animpow public key alinamadi.")
        }

        val keyBytes = Base64.decode(
            keyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), ""),
            Base64.DEFAULT,
        )
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private fun randomAesKey(): SecretKey {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return SecretKeySpec(bytes, "AES")
    }

    private fun encryptAesKey(publicKey: RSAPublicKey, aesKey: SecretKey): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            publicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        return Base64.encodeToString(cipher.doFinal(aesKey.encoded), Base64.NO_WRAP)
    }

    private fun decryptEnvelope(envelope: JsonObject): JsonObject {
        val key = aesKey ?: throw Exception("Animpow session hazir degil.")
        val iv = envelope.string("iv") ?: return envelope
        val data = envelope.string("data") ?: return envelope
        val authTag = envelope.string("authTag") ?: return envelope

        val encryptedBytes = Base64.decode(data, Base64.DEFAULT)
        val tagBytes = Base64.decode(authTag, Base64.DEFAULT)
        val cipherText = encryptedBytes + tagBytes

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8).asJsonObject()
    }

    @Synchronized
    private fun resetSession() {
        sessionId = null
        aesKey = null
    }

    private fun String.asJsonObject(): JsonObject = json.parseToJsonElement(this).jsonObject

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        private const val API_BASE = "https://client-api.animpow.com/api/v1"
    }
}
