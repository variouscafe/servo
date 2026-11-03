/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import dev.namsan.core.NamsanCore
import java.io.File
import org.servo.servoshell.data.MetadataFetcher
import org.servo.servoshell.data.WebAppRepository

/**
 * 남산(경로③ servo 엔진) 앱. 레지스트리·URL 정책은 WebView판과 동일한
 * Rust 코어(libnamsan_core.so, UniFFI)를 쓴다 — 정책·데이터는 Rust에.
 */
class NamsanApp : Application() {
    val core: NamsanCore by lazy {
        NamsanCore(File(filesDir, "namsan.db").absolutePath)
    }

    val iconCache: LruCache<String, Bitmap> = LruCache(48)

    val repository: WebAppRepository by lazy {
        WebAppRepository(core, MetadataFetcher(), iconCache)
    }

    override fun onCreate() {
        super.onCreate()
        NamsanPush.init(this)
    }
}
