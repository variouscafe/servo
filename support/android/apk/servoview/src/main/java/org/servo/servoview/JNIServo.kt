/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoview

import android.content.Context
import android.util.Size
import android.view.Surface

/** Maps /ports/servoshell API */
internal class JNIServo {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("servoshell")
    }

    external fun init(
        context: Context,
        args: String?,
        url: String?,
        logStr: String?,
        experimentalMode: Boolean,
        callbacks: Callbacks,
    )

    external fun addPlatformWindow(
        size: Size,
        density: Float,
        surface: Surface,
    )

    external fun performUpdates()

    external fun resize(size: Size)

    external fun reload()

    external fun stop()

    external fun goBack()

    external fun goForward()

    external fun loadUri(uri: String)

    /** namsan: 앱 전환 — 상주 웹뷰 활성화(엔진 풀링, 재로드 없음). */
    external fun namsanOpenApp(appId: String, uri: String)

    external fun keydown(keycode: Int, unicode: Int)

    external fun keyup(keycode: Int, unicode: Int)

    external fun touchDown(x: Float, y: Float, pointer_id: Int)

    external fun touchMove(x: Float, y: Float, pointer_id: Int)

    external fun touchUp(x: Float, y: Float, pointer_id: Int)

    external fun touchCancel(x: Float, y: Float, pointer_id: Int)

    external fun pausePainting()

    external fun resumePainting(surface: Surface, size: Size)

    external fun setExperimentalMode(enable: Boolean)

    external fun doFrame()

    interface Callbacks {
        fun wakeup()

        fun onAlert(message: String)

        // namsan: Notification API — 웹 페이지가 띄운 알림(앱이 시스템 노티로 표시).
        fun onNotification(title: String, body: String)

        // namsan: 알림 권한 판정(동기). 앱 설정 + 시스템 권한 상태로 즉시 결정.
        fun onRequestNotificationPermission(): Boolean

        // namsan push bridge: Web Push 구독 생성(동기, 일회성).
        // {endpoint,p256dh,auth} JSON 또는 null(구독 실패/거부).
        fun onPushSubscribe(origin: String): String?

        // namsan push bridge: 저장된 구독만 조회(동기, 네트워크 없음).
        // pushManager.getSubscription() — 없으면 null(새로 만들지 않는다).
        fun onPushGetSubscription(origin: String): String?

        // namsan: 정책상 외부로 위임할 내비게이션(앱이 시스템 브라우저/앱으로 연다).
        fun onExternalNavigation(url: String)

        fun onLoadStarted()

        fun onLoadEnded()

        fun onTitleChanged(title: String)

        fun onUrlChanged(url: String)

        fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean)

        fun onImeShow()

        fun onImeHide()
    }
}
