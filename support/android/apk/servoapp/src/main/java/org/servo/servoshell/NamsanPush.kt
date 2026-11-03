/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * namsan push bridge (경로③) — WebView에 없는 Web Notifications/Push를 servo
 * 엔진에서 쓸 수 있게 하는 앱 측 구현.
 *
 *  - 포그라운드: 엔진의 Notification API → [showWebNotification] → 시스템 노티
 *  - 구독: pushManager.subscribe() → [subscribeBlocking] → P-256 키 생성 +
 *    Cloudflare 릴레이에 FCM 토큰 등록 → 표준 PushSubscription JSON 반환
 *  - 백그라운드: 사이트 → 릴레이(RFC 8030) → FCM data 메시지 →
 *    [handlePushMessage] → RFC 8291(aes128gcm) 복호화 → 시스템 노티
 *
 * 설정: assets/namsan_push.json — { relayUrl, firebase:{projectId,apiKey,
 * applicationId,gcmSenderId} }. 파일이 없으면 푸시 비활성(포그라운드 알림은 동작).
 */
object NamsanPush {
    /** P-256 도메인 파라미터 — 호출마다 키쌍을 만들어 얻지 않도록 캐시. */
    private val p256Params: ECParameterSpec by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        (generator.generateKeyPair().public as ECPublicKey).params
    }
    private const val PREFS = "namsan_push"
    private const val KEY_SUBS = "subscriptions" // origin -> 구독 정보
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val CHANNEL_WEB = "namsan_web"

    private var config: JSONObject? = null
    private var appContext: Context? = null
    private val http = OkHttpClient()

    data class Subscription(
        val origin: String,
        val subId: String,
        val endpoint: String,
        val p256dhPubB64u: String,
        val p256dhPrivB64u: String,
        val authB64u: String,
        /** 릴레이 재바인딩 방지 비밀값(토큰 로테이션 시 갱신 인증에 사용). */
        val secret: String,
    )

    /** Application.onCreate에서 1회 호출. Firebase 수동 초기화도 여기서. */
    fun init(context: Context) {
        appContext = context.applicationContext
        config = runCatching {
            JSONObject(
                context.assets.open("namsan_push.json").bufferedReader().use { it.readText() },
            )
        }.getOrNull()
        val fb = config?.optJSONObject("firebase") ?: return
        runCatching {
            com.google.firebase.FirebaseApp.initializeApp(
                context,
                com.google.firebase.FirebaseOptions.Builder()
                    .setProjectId(fb.getString("projectId"))
                    .setApiKey(fb.getString("apiKey"))
                    .setApplicationId(fb.getString("applicationId"))
                    .setGcmSenderId(fb.getString("gcmSenderId"))
                    .build(),
            )
        }
    }

    private fun relayUrl(): String? = config?.optString("relayUrl")?.takeIf { it.isNotBlank() }

    private fun prefs(): SharedPreferencesLite? = appContext?.let {
        SharedPreferencesLite(it.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    /** 저장소 접근을 한 곳으로 모은 얇은 래퍼(테스트 용이성). */
    class SharedPreferencesLite(val prefs: android.content.SharedPreferences) {
        fun subs(): JSONObject =
            runCatching { JSONObject(prefs.getString(KEY_SUBS, "{}")!!) }.getOrDefault(JSONObject())
        fun saveSubs(map: JSONObject) = prefs.edit().putString(KEY_SUBS, map.toString()).apply()
        var fcmToken: String?
            get() = prefs.getString(KEY_FCM_TOKEN, null)
            set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()
    }

    // ── 엔진 콜백 ───────────────────────────────────────────────

    /** 알림 권한: 앱 설정 토글(기본 허용) + Android 13+ 런타임 권한. */
    fun notificationPermission(context: Context): Boolean {
        val enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("notifications_enabled", true)
        if (!enabled) return false
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /**
     * pushManager.getSubscription() 처리 — 저장된 구독만 조회한다.
     * 네트워크·키 생성 없이 즉시 반환. 없으면 null(페이지의 프라미스는
     * null로 귀결 — 표준 사양. 이전처럼 구독을 새로 만들지 않는다).
     */
    fun getSubscription(origin: String): String? {
        val store = prefs() ?: return null
        val sub = loadSubscription(store, origin) ?: return null
        return runCatching {
            JSONObject()
                .put("endpoint", sub.endpoint)
                .put("p256dh", sub.p256dhPubB64u)
                .put("auth", sub.authB64u)
                .toString()
        }.getOrNull()
    }

    /**
     * pushManager.subscribe() 처리. 별도 스레드에서 호출된다(엔진이 GL
     * 스레드를 멈추지 않도록 워커로 옮김) — 네트워크 왕복(수백 ms) 포함.
     * 성공 시 {endpoint,p256dh,auth} JSON.
     */
    fun subscribeBlocking(origin: String): String? {
        val relay = relayUrl() ?: return null
        val store = prefs() ?: return null
        if (config?.optJSONObject("firebase") == null) return null
        return runCatching {
            val sub = loadSubscription(store, origin) ?: run {
                val token = store.fcmToken ?: fetchFcmToken(store) ?: return null
                createSubscription(relay, store, origin, token) ?: return null
            }
            JSONObject()
                .put("endpoint", sub.endpoint)
                .put("p256dh", sub.p256dhPubB64u)
                .put("auth", sub.authB64u)
                .toString()
        }.getOrNull()
    }

    /** FCM 메시지 수신: subId + base64url(aes128gcm body) → 복호화 → 노티. */
    fun handlePushMessage(context: Context, subId: String, ciphertextB64u: String) {
        // 앱 알림 설정 토글·시스템 권한이 꺼져 있으면 표시하지 않는다
        // (수신·복호화는 유효 — 표시만 게이트).
        if (!notificationPermission(context)) return
        val store = prefs() ?: return
        val sub = allSubscriptions(store).firstOrNull { it.subId == subId } ?: return
        val plaintext = runCatching {
            decryptAes128Gcm(
                body = b64uDecode(ciphertextB64u),
                receiverPrivRaw = b64uDecode(sub.p256dhPrivB64u),
                auth = b64uDecode(sub.authB64u),
                receiverPubRaw = b64uDecode(sub.p256dhPubB64u),
            )
        }.getOrNull()
        val (title, body) = parsePayload(plaintext, sub)
        showWebNotification(context, title, body, sub.origin)
    }

    fun showWebNotification(context: Context, title: String, body: String, origin: String? = null) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WEB, "웹 알림", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val intent = if (origin != null) {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(origin))
        } else {
            Intent(context, MainActivity::class.java)
        }
        val pending = PendingIntent.getActivity(
            context, origin?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_WEB)
            .setSmallIcon(R.drawable.ic_stat_web)
            .setContentTitle(title.ifBlank { "웹 알림" })
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        // 사이트별 스택: (안정 id) xor (수신 시각) — 개별 노티가 쌓인다.
        val id = (origin?.hashCode() ?: 1) xor System.currentTimeMillis().toInt()
        manager.notify(id, notification)
    }

    /** FCM 토큰 갱신 시 저장된 구독을 새 토큰으로 재등록한다. */
    fun onNewFcmToken(token: String) {
        val store = prefs() ?: return
        store.fcmToken = token
        val relay = relayUrl() ?: return
        Thread {
            allSubscriptions(store).forEach { sub ->
                runCatching { relayRegister(relay, token, sub.subId, sub.secret) }
            }
        }.start()
    }

    // ── 릴레이 통신 ─────────────────────────────────────────────

    private fun fetchFcmToken(store: SharedPreferencesLite): String? = runCatching {
        com.google.android.gms.tasks.Tasks.await(
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token,
        )
    }.getOrNull()?.also { store.fcmToken = it }

    private fun relayRegister(
        relay: String,
        token: String,
        existingSubId: String? = null,
        existingSecret: String? = null,
    ): JSONObject? {
        val payload = JSONObject().put("token", token).also {
            if (existingSubId != null) {
                it.put("subId", existingSubId)
                it.put("secret", existingSecret)
            }
        }
        val request = Request.Builder()
            .url("$relay/register")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return runCatching { JSONObject(response.body?.string()!!) }.getOrNull()
        }
    }

    private fun createSubscription(
        relay: String,
        store: SharedPreferencesLite,
        origin: String,
        fcmToken: String,
    ): Subscription? {
        val (pubB64u, privB64u) = generateP256KeyPair()
        val authB64u = base64Url(SecureRandom().generateSeed(16))
        val reg = relayRegister(relay, fcmToken) ?: return null
        val sub = Subscription(
            origin = origin,
            subId = reg.getString("subId"),
            endpoint = reg.getString("endpoint"),
            p256dhPubB64u = pubB64u,
            p256dhPrivB64u = privB64u,
            authB64u = authB64u,
            secret = reg.getString("secret"),
        )
        val map = store.subs()
        map.put(origin, subToJson(sub))
        store.saveSubs(map)
        return sub
    }

    // ── 저장 ────────────────────────────────────────────────────

    private fun subToJson(sub: Subscription): JSONObject = JSONObject()
        .put("subId", sub.subId)
        .put("endpoint", sub.endpoint)
        .put("p256dhPub", sub.p256dhPubB64u)
        .put("p256dhPriv", sub.p256dhPrivB64u)
        .put("auth", sub.authB64u)
        .put("secret", sub.secret)

    private fun subFromJson(origin: String, s: JSONObject) = Subscription(
        origin, s.getString("subId"), s.getString("endpoint"),
        s.getString("p256dhPub"), s.getString("p256dhPriv"), s.getString("auth"),
        s.optString("secret"),
    )

    private fun loadSubscription(store: SharedPreferencesLite, origin: String): Subscription? {
        val s = store.subs().optJSONObject(origin) ?: return null
        return subFromJson(origin, s)
    }

    private fun allSubscriptions(store: SharedPreferencesLite): List<Subscription> {
        val map = store.subs()
        return map.keys().asSequence().map { origin ->
            subFromJson(origin, map.getJSONObject(origin))
        }.toList()
    }

    // ── 암호(RFC 8291 aes128gcm 수신 측) ───────────────────────

    /**
     * body = salt[16] || rs[4] || idlen[1] || keyid[idlen] || AES-GCM(ct||tag).
     * keyid는 발신자(사이트 서버) 공개키(비압축 65B). 반환: 평문(패딩 제거).
     */
    fun decryptAes128Gcm(
        body: ByteArray,
        receiverPrivRaw: ByteArray,
        auth: ByteArray,
        receiverPubRaw: ByteArray,
    ): ByteArray {
        val salt = body.copyOfRange(0, 16)
        val idLen = body[20].toInt() and 0xFF
        val senderPub = body.copyOfRange(21, 21 + idLen)
        val ciphertext = body.copyOfRange(21 + idLen, body.size)

        val ikm = ecdhP256(fixLength(receiverPrivRaw, 32), senderPub)
        val prk = hkdfExtract(salt, ikm)
        val cekInfo = "WebPush: info".toByteArray() + byteArrayOf(0) + auth + receiverPubRaw
        val cek = hkdfExpand(prk, cekInfo, 16)
        val nonce = hkdfExpand(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0), 12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        var plaintext = cipher.doFinal(ciphertext)
        // RFC 8291 aes128gcm 패딩: plaintext = message || 0x02 || 0x00*.
        // 끝에서 0x00을 전부 벗긴 뒤 0x02 구분자가 남아야 한다(패딩 없으면 0x02만).
        var end = plaintext.size
        while (end > 0 && plaintext[end - 1] == 0x00.toByte()) end--
        require(end > 0 && plaintext[end - 1] == 0x02.toByte()) { "aes128gcm 패딩 구분자 없음" }
        plaintext = plaintext.copyOf(end - 1)
        return plaintext
    }

    /** P-256 키쌍 생성 → (공개키 raw b64url, 개인키 d b64url). */
    fun generateP256KeyPair(): Pair<String, String> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val pub = pubKeyToRaw(keyPair.public as ECPublicKey)
        val priv = fixLength((keyPair.private as java.security.interfaces.ECPrivateKey).s.toByteArray(), 32)
        return base64Url(pub) to base64Url(priv)
    }

    /** ECDH(개인키 raw d, 상대 공개키 raw 65B) → 공유 비밀 32B. JDK 표준 API만 사용. */
    private fun ecdhP256(privRaw: ByteArray, peerPubRaw65: ByteArray): ByteArray {
        require(peerPubRaw65.size == 65 && peerPubRaw65[0] == 0x04.toByte()) {
            "발신자 공개키는 비압축 65B여야 한다"
        }
        fun coord(off: Int) = BigInteger(1, peerPubRaw65.copyOfRange(off, off + 32))

        val peerPub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(coord(1), coord(33)), p256Params),
        )
        val privKey = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(ecPkcs8PrivateKey(privRaw)),
        )
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privKey)
        agreement.doPhase(peerPub, true)
        // 공유 비밀 x좌표는 최소 BigInteger 인코딩으로 31B 이하가 될 수 있다(~1/256).
        // HKDF IKM은 항상 32B 고정이므로 zero-pad한다(RFC 8291 참조 구현과 동일).
        return fixLength(agreement.generateSecret(), 32)
    }

    /** 스칼라 d(32B) → PKCS#8 EC(P-256) 개인키 DER. 미니 인코더로 직접 조립. */
    private fun ecPkcs8PrivateKey(d: ByteArray): ByteArray {
        fun der(length: Int, tag: Byte, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag.toInt())
            require(length < 128) { "길이 인코딩은 1바이트로 충분해야 한다" }
            out.write(length)
            out.write(content)
            return out.toByteArray()
        }

        val version = byteArrayOf(2, 1, 0) // PKCS#8 version 0
        val algId = byteArrayOf(0x30, 0x13, 0x06, 0x07) +
            byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0xCE.toByte(), 0x3D, 0x02, 0x01) + // ecPublicKey
            byteArrayOf(0x06, 0x08) +
            byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07) // P-256
        val ecPriv = der(3 + 2 + d.size, 0x30, byteArrayOf(2, 1, 1) + der(d.size, 0x04, d))
        val inner = version + algId + der(ecPriv.size, 0x04, ecPriv)
        return der(inner.size, 0x30, inner)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    // ── 인코딩 유틸 ────────────────────────────────────────────

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64uDecode(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun fixLength(bytes: ByteArray, length: Int): ByteArray {
        if (bytes.size == length) return bytes
        val out = ByteArray(length)
        if (bytes.size > length) {
            System.arraycopy(bytes, bytes.size - length, out, 0, length)
        } else {
            System.arraycopy(bytes, 0, out, length - bytes.size, bytes.size)
        }
        return out
    }

    private fun pubKeyToRaw(pub: ECPublicKey): ByteArray {
        fun bigInt(value: BigInteger) = fixLength(value.toByteArray(), 32)
        val out = ByteArray(65)
        out[0] = 0x04
        System.arraycopy(bigInt(pub.w.affineX), 0, out, 1, 32)
        System.arraycopy(bigInt(pub.w.affineY), 0, out, 33, 32)
        return out
    }

    private fun parsePayload(plaintext: ByteArray?, sub: Subscription): Pair<String, String> {
        val text = plaintext?.toString(Charsets.UTF_8)
        val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json != null) {
            return json.optString("title", sub.origin) to json.optString("body", text ?: "")
        }
        return (text?.takeIf { it.isNotBlank() } ?: "새 알림") to ""
    }
}
