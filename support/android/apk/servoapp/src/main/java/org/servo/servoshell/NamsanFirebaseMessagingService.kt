/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * namsan push bridge: 릴레이가 중계한 Web Push(aes128gcm 암호문)를 FCM
 * data 메시지로 받아 복호화·표시한다. 앱이 꺼져 있어도 Doze를 뚫고 온다.
 */
class NamsanFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val subId = data["sub"] ?: return
        val ciphertext = data["ct"] ?: return
        NamsanPush.handlePushMessage(this, subId, ciphertext)
    }

    override fun onNewToken(token: String) {
        NamsanPush.onNewFcmToken(token)
    }
}
