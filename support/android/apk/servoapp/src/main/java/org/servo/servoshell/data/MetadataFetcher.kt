package org.servo.servoshell.data

import dev.namsan.core.IconCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 메타데이터 HTTP 조회(OkHttp). 파싱/정책은 Rust 코어가 담당한다.
 * TLS 크로스컴파일 부담이 없는 Kotlin이 네트워크를 맡기로 한 설계 선택.
 */
class MetadataFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    /** HTML 본문을 최대 [MAX_HTML_BYTES]까지 내려받는다. 실패 시 null. */
    suspend fun fetchHtml(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val raw = response.body?.bytes() ?: return@use null
                val capped = if (raw.size > MAX_HTML_BYTES) raw.copyOf(MAX_HTML_BYTES) else raw
                String(capped, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /** 후보 아이콘 중 사용 가능한 것을 순서대로 시도해 내려받는다. */
    suspend fun fetchIconBytes(candidates: List<IconCandidate>): ByteArray? = withContext(Dispatchers.IO) {
        candidates.asSequence().take(4).forEach { candidate ->
            val bytes = runCatching {
                client.newCall(Request.Builder().url(candidate.url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val raw = response.body?.bytes() ?: return@use null
                    if (raw.isEmpty() || raw.size > MAX_ICON_BYTES) null else raw
                }
            }.getOrNull()
            if (bytes != null) {
                // 구형 파비콘(.ico)은 컨테이너 — 내장 PNG 프레임(Vista+ 아이콘)을 꺼내 쓴다.
                val image = if (isIco(bytes)) extractLargestPngFrame(bytes) ?: bytes else bytes
                if (looksLikeImage(image)) return@withContext image
            }
        }
        null
    }

    /** ICO 컨테이너(예비 2B=0, 타입 2B=1). */
    private fun isIco(b: ByteArray): Boolean =
        b.size > 6 && b[0] == 0.toByte() && b[1] == 0.toByte() &&
            b[2] == 1.toByte() && b[3] == 0.toByte()

    /**
     * ICO 디렉터리 엔트리를 훑어 가장 큰 내장 PNG 프레임을 추출한다.
     * BMP 프레임은 지원하지 않는다(현대 아이콘은 대부분 PNG 내장).
     */
    private fun extractLargestPngFrame(ico: ByteArray): ByteArray? {
        val count = ((ico[4].toInt() and 0xFF) shl 8) or (ico[5].toInt() and 0xFF)
        if (count <= 0 || count > 64) return null
        var best: ByteArray? = null
        for (i in 0 until count) {
            val entry = 6 + i * 16
            if (entry + 16 > ico.size) break
            val size = readIntLE(ico, entry + 8)
            val offset = readIntLE(ico, entry + 12)
            if (size <= 0 || offset < 0 || offset + size > ico.size) continue
            if (size > 8 &&
                ico[offset].toInt() and 0xFF == 0x89 &&
                ico[offset + 1].toInt() and 0xFF == 0x50
            ) {
                val frame = ico.copyOfRange(offset, offset + size)
                if (best == null || frame.size > best.size) best = frame
            }
        }
        return best
    }

    private fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun looksLikeImage(b: ByteArray): Boolean = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> true  // PNG
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> true  // JPEG
        b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF"
            && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> true            // WebP
        else -> false
    }

    companion object {
        private const val MAX_HTML_BYTES = 512 * 1024
        private const val MAX_ICON_BYTES = 1024 * 1024
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
    }
}
