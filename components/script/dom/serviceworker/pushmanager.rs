/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

//! namsan push bridge (경로③): [Push API](https://w3c.github.io/push-api/)의
//! 최소 구독 표면. 사이트가 수정 없이 표준 코드로 구독할 수 있게 한다.
//!
//! 실제 구독 생성(키 생성·릴레이 등록)은 임베더(Android 앱)가 수행한다.
//! `subscribe()`의 프라미스는 임베더가 별도 스레드에서 구독을 마친 뒤
//! 채워진다(스크립트 스레드는 응답까지 대기 — 일회성이라 허용).

use dom_struct::dom_struct;
use js::context::JSContext;
use script_bindings::reflector::{Reflector, reflect_dom_object};
use servo_base::generic_channel;

use crate::dom::bindings::codegen::Bindings::PushManagerBinding::PushManagerMethods;
use crate::dom::bindings::error::Error;
use crate::dom::bindings::reflector::DomGlobal;
use crate::dom::bindings::root::DomRoot;
use crate::dom::globalscope::GlobalScope;
use crate::dom::promise::{Promise, RootedPromise};
use crate::dom::serviceworker::pushsubscription::PushSubscription;
use embedder_traits::{EmbedderMsg, PushSubscriptionData};

#[dom_struct]
pub(crate) struct PushManager {
    reflector_: Reflector,
}

impl PushManager {
    fn new_inherited() -> PushManager {
        PushManager {
            reflector_: Reflector::new(),
        }
    }

    #[cfg_attr(crown, expect(crown::unrooted_must_root))]
    pub(crate) fn new(cx: &mut JSContext, global: &GlobalScope) -> DomRoot<PushManager> {
        let manager = PushManager::new_inherited();
        reflect_dom_object(cx, Box::new(manager), global)
    }

    /// 임베더에 구독 생성(create)/조회를 요청한다. 채널이 닫히거나 `None`
    /// 응답은 getSubscription의 null / subscribe의 거부로 취급한다.
    /// 조회(create == false)에서는 임베더가 아무것도 등록해선 안 된다.
    fn request_subscription(&self, create: bool) -> Option<PushSubscriptionData> {
        let global = self.global();
        let (sender, receiver) = generic_channel::channel()?;
        let origin = global.get_url().origin().ascii_serialization().into_owned();
        global.send_to_embedder(EmbedderMsg::RequestPushSubscription(
            global.webview_id(),
            origin,
            create,
            sender,
        ));
        receiver.recv().ok().flatten()
    }
}

impl PushManagerMethods<crate::DomTypeHolder> for PushManager {
    /// <https://w3c.github.io/push-api/#dom-pushmanager-subscribe>
    /// namsan: 옵션 인자는 무시한다 — applicationServerKey(VAPID)는 사이트
    /// 서버가 전송 시 사용하는 값이며 구독 생성에 반영할 필요가 없다.
    fn Subscribe(&self, cx: &mut JSContext) -> RootedPromise {
        let promise = Promise::new(cx, &self.global());
        match self.request_subscription(true) {
            Some(data) => {
                let subscription = PushSubscription::new(cx, &self.global(), data);
                promise.resolve_native(cx, &subscription);
            },
            None => {
                promise.reject_error(
                    cx,
                    Error::Abort(Some("Push subscription denied or unavailable".into())),
                );
            },
        }
        promise
    }

    /// <https://w3c.github.io/push-api/#dom-pushmanager-getsubscription>
    /// 조회 전용 — 없으면 null로 귀결한다. 사양과 달리 구독을 새로 만들지
    /// 않는다(이전 동작은 키 생성·릴레이 등록을 수반하는 사양 위반이었다).
    fn GetSubscription(&self, cx: &mut JSContext) -> RootedPromise {
        let promise = Promise::new(cx, &self.global());
        match self.request_subscription(false) {
            Some(data) => {
                let subscription = PushSubscription::new(cx, &self.global(), data);
                promise.resolve_native(cx, &subscription);
            },
            None => promise.resolve_native(cx, &Option::<DomRoot<PushSubscription>>::None),
        }
        promise
    }
}
