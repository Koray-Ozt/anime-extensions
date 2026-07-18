package eu.kanade.tachiyomi.animeextension.tr.animpow

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimpowApiClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val sourceHeaders: Headers,
) {

    private var publicKey: RSAPublicKey? = null
    @Volatile
    private var session: SecureSession? = null

    private val sessionMutex = Mutex()

    suspend fun get(path: String, vararg queryParameters: Pair<String, String?>): JsonObject {
        repeat(2) { attempt ->
            val currentSession = ensureSession()
            val response = secureGet(path, queryParameters, currentSession.id)
            val envelope = response.body.asJsonObject()

            if (response.code == 400 && envelope.isSessionError() && attempt == 0) {
                resetSession(currentSession)
                return@repeat
            }

            if (response.code !in 200..299) {
                throw Exception(envelope.string("message") ?: envelope.string("error") ?: "Animpow API hatasi: ${response.code}")
            }

            return decryptEnvelope(envelope, currentSession.key)
        }
        throw Exception("Animpow guvenli oturumu yenilenemedi.")
    }

    private suspend fun secureGet(
        path: String,
        queryParameters: Array<out Pair<String, String?>>,
        sessionId: String,
    ): ApiResponse {
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
                .set("X-Session-Id", sessionId)
                .build(),
        )

        return client.newCall(request).await().use { ApiResponse(it.code, it.body.string()) }
    }

    private suspend fun ensureSession(): SecureSession = session ?: sessionMutex.withLock {
        session ?: createSession().also { session = it }
    }

    private suspend fun createSession(): SecureSession {
        val key = publicKey ?: fetchPublicKey().also { publicKey = it }
        val aesKey = randomAesKey()
        val sessionId = UUID.randomUUID().toString()
        val encryptedKey = encryptAesKey(key, aesKey)

        val body = """{"sessionId":"$sessionId","encryptedKey":"$encryptedKey"}""".toJsonRequestBody()
        val request = POST(
            "$API_BASE/auth/handshake",
            sourceHeaders.newBuilder()
                .set("Accept", "application/json, text/plain, */*")
                .set("Content-Type", "application/json")
                .build(),
            body = body,
        )

        client.newCall(request).awaitSuccess().close()
        return SecureSession(sessionId, aesKey)
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
        // Some Android crypto providers silently force MGF1-SHA1 for OAEP. The
        // website requires SHA-256 for both OAEP digests, so encode OAEP here and
        // let the provider perform only the raw RSA operation.
        val encoded = oaepEncode(aesKey.encoded, (publicKey.modulus.bitLength() + 7) / 8)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(encoded), Base64.NO_WRAP)
    }

    private fun oaepEncode(message: ByteArray, encodedLength: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashLength = digest.digestLength
        require(message.size <= encodedLength - (2 * hashLength) - 2) { "Animpow anahtari RSA icin cok uzun." }

        val dataBlock = ByteArray(encodedLength - hashLength - 1)
        digest.digest(ByteArray(0)).copyInto(dataBlock)
        dataBlock[dataBlock.size - message.size - 1] = 1
        message.copyInto(dataBlock, dataBlock.size - message.size)

        val seed = ByteArray(hashLength).also(secureRandom::nextBytes)
        val maskedDataBlock = xor(dataBlock, mgf1(seed, dataBlock.size))
        val maskedSeed = xor(seed, mgf1(maskedDataBlock, hashLength))
        return byteArrayOf(0) + maskedSeed + maskedDataBlock
    }

    private fun mgf1(seed: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        var offset = 0
        var counter = 0
        while (offset < length) {
            val counterBytes = byteArrayOf(
                (counter ushr 24).toByte(),
                (counter ushr 16).toByte(),
                (counter ushr 8).toByte(),
                counter.toByte(),
            )
            val block = MessageDigest.getInstance("SHA-256").digest(seed + counterBytes)
            val count = minOf(block.size, length - offset)
            block.copyInto(output, offset, 0, count)
            offset += count
            counter++
        }
        return output
    }

    private fun xor(left: ByteArray, right: ByteArray): ByteArray =
        ByteArray(left.size) { index -> (left[index].toInt() xor right[index].toInt()).toByte() }

    private fun decryptEnvelope(envelope: JsonObject, key: SecretKey): JsonObject {
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

    private suspend fun resetSession(expiredSession: SecureSession) = sessionMutex.withLock {
        if (session === expiredSession) session = null
    }

    private fun String.asJsonObject(): JsonObject = json.parseToJsonElement(this).jsonObject

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.isSessionError(): Boolean = string("error") in SESSION_ERRORS

    private data class SecureSession(val id: String, val key: SecretKey)

    private data class ApiResponse(val code: Int, val body: String)

    companion object {
        private const val API_BASE = "https://client-api.animpow.com/api/v1"
        private val SESSION_ERRORS = setOf(
            "Invalid or expired session key",
            "Encryption is required for this API route",
            "Encryption is required for this API route.",
        )
        private val secureRandom = SecureRandom()
    }
}
