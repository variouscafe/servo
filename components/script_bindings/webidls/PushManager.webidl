/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// https://w3c.github.io/push-api/#pushmanager-interface
// namsan push bridge (경로③): 사이트가 표준 코드 그대로 구독할 수 있게 하는
// 최소 표면. subscribe()의 옵션 인자는 받아서 무시한다 — applicationServerKey
// (VAPID)는 사이트 *서버*가 전송 시 서명에 사용하는 값이며 엔드포인트나 키
// 생성에 반영할 필요가 없다. 구독 생성은 임베더(앱)가 수행한다.
[Pref="dom_serviceworker_enabled", SecureContext, Exposed=(Window,Worker)]
interface PushManager {
    [NewObject]
    Promise<PushSubscription> subscribe();

    [NewObject]
    Promise<PushSubscription?> getSubscription();
};
