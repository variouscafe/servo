/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// https://w3c.github.io/push-api/#pushsubscription-interface
// namsan push bridge: 구독 정보의 최소 표면. 사이트는 이 값을 그대로 자기
// 서버에 POST 한다({endpoint, keys:{p256dh, auth}} — toJSON() 참고).
[Pref="dom_serviceworker_enabled", SecureContext, Exposed=(Window,Worker)]
interface PushSubscription {
    readonly attribute USVString endpoint;

    [SameObject] readonly attribute PushSubscriptionKeys keys;

    [Default] object toJSON();
};

// https://w3c.github.io/push-api/#pushsubscriptionkeys-interface
[Pref="dom_serviceworker_enabled", SecureContext, Exposed=(Window,Worker)]
interface PushSubscriptionKeys {
    readonly attribute DOMString p256dh;

    readonly attribute DOMString auth;
};
