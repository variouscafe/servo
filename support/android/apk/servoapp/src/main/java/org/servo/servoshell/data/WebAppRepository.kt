package org.servo.servoshell.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.namsan.core.CoreException
import dev.namsan.core.Metadata
import dev.namsan.core.NavDecision
import dev.namsan.core.NamsanCore
import dev.namsan.core.WebAppSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 앱 추가 실패 사유 — UI가 사용자에게 그대로 보여줄 수 있는 형태로 변환한 것. */
sealed class AddAppException(message: String) : Exception(message) {
    class InvalidUrl(reason: String) : AddAppException(reason)
    class Duplicate(url: String) : AddAppException("이미 추가된 사이트입니다: $url")
    class Unknown(reason: String) : AddAppException(reason)
}

/**
 * 웹앱 데이터 소스. 모든 FFI 호출은 coarse-grained로 묶어 IO 디스패처에서 실행.
 * classify만 예외: 순수 계산(µs급)이라 메인 스레드 동기 호출.
 */
class WebAppRepository(
    private val core: NamsanCore,
    private val fetcher: MetadataFetcher,
    private val iconCache: LruCache<String, Bitmap>,
) {
    /** 최근 listApps 결과. 앱 오픈 시 DB 왕복 없이 WebView 생성을 바로 시작하게 한다. */
    @Volatile
    private var appsCache: List<WebAppSummary> = emptyList()

    /** 캐시에서만 조회(메인 스레드용, miss 시 null). */
    fun peekApp(id: String): WebAppSummary? = appsCache.firstOrNull { it.id == id }

    suspend fun listApps(): List<WebAppSummary> = withContext(Dispatchers.IO) {
        core.listApps().also { appsCache = it }
    }

    suspend fun deleteApp(id: String) = withContext(Dispatchers.IO) { core.deleteApp(id) }

    suspend fun renameApp(id: String, name: String) = withContext(Dispatchers.IO) { core.renameApp(id, name) }

    suspend fun markOpened(id: String) = withContext(Dispatchers.IO) { core.markOpened(id) }

    fun classifyNow(appUrl: String, targetUrl: String): NavDecision =
        core.classifyNavigation(appUrl, targetUrl)

    suspend fun icon(id: String): Bitmap? {
        iconCache.get(id)?.let { return it }
        return withContext(Dispatchers.IO) {
            core.getIcon(id)?.let { bytes -> decodeIcon(bytes)?.also { iconCache.put(id, it) } }
        }
    }

    /** URL 정규화 → 메타데이터 조회 → 앱 저장 → 아이콘 저장. 파이프라인 전체를 한 번에. */
    suspend fun addApp(input: String): WebAppSummary = withContext(Dispatchers.IO) {
        try {
            val url = core.normalizeUrl(input)
            val html = fetcher.fetchHtml(url)
            val meta = if (html != null) core.extractMetadata(html, url)
                       else Metadata(title = null, themeColor = null, icons = emptyList())
            val app = core.createApp(url, meta.title, meta.themeColor)
            fetcher.fetchIconBytes(meta.icons)?.let { bytes ->
                core.setIcon(app.id, bytes)
                decodeIcon(bytes)?.let { iconCache.put(app.id, it) }
            }
            appsCache = core.listApps()   // 등록 직후 오픈이 캐시 히트 되도록
            app
        } catch (e: CoreException.Duplicate) {
            throw AddAppException.Duplicate(e.url)
        } catch (e: CoreException.InvalidUrl) {
            throw AddAppException.InvalidUrl(e.detail)
        } catch (e: CoreException) {
            throw AddAppException.Unknown(e.toString())
        }
    }

    private fun decodeIcon(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, (maxOf(bounds.outWidth, bounds.outHeight) / 192).coerceAtLeast(1))
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}
