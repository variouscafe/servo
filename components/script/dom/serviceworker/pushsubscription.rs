/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

//! namsan push bridge: 임베더가 만든 Web Push 구독을 페이지에 노출하는 값 객체.
//! `toJSON()`은 코드젠이 속성(endpoint, keys)으로 자동 직렬화한다 — 사이트는
//! 이 값을 그대로 자기 서버에 POST 한다.

use dom_struct::dom_struct;
use js::context::JSContext;
use script_bindings::reflector::{Reflector, reflect_dom_object};

use crate::dom::bindings::codegen::Bindings::PushSubscriptionBinding::PushSubscriptionMethods;
use crate::dom::bindings::root::{Dom, DomRoot};
use crate::dom::bindings::str::USVString;
use crate::dom::globalscope::GlobalScope;
use crate::dom::pushsubscriptionkeys::PushSubscriptionKeys;
use embedder_traits::PushSubscriptionData;

#[dom_struct]
pub(crate) struct PushSubscription {
    reflector_: Reflector,
    /// <https://w3c.github.io/push-api/#dom-pushsubscription-endpoint>
    endpoint: USVString,
    /// <https://w3c.github.io/push-api/#dom-pushsubscription-keys>
    keys: Dom<PushSubscriptionKeys>,
}

impl PushSubscription {
    fn new_inherited(endpoint: USVString, keys: DomRoot<PushSubscriptionKeys>) -> PushSubscription {
        PushSubscription {
            reflector_: Reflector::new(),
            endpoint,
            keys: Dom::from_ref(&*keys),
        }
    }

    #[cfg_attr(crown, expect(crown::unrooted_must_root))]
    pub(crate) fn new(
        cx: &mut JSContext,
        global: &GlobalScope,
        data: PushSubscriptionData,
    ) -> DomRoot<PushSubscription> {
        let keys = PushSubscriptionKeys::new(cx, global, data.p256dh, data.auth);
        let subscription = PushSubscription::new_inherited(USVString(data.endpoint), keys);
        reflect_dom_object(cx, Box::new(subscription), global)
    }
}

impl PushSubscriptionMethods<crate::DomTypeHolder> for PushSubscription {
    /// <https://w3c.github.io/push-api/#dom-pushsubscription-endpoint>
    fn Endpoint(&self) -> USVString {
        self.endpoint.clone()
    }

    /// <https://w3c.github.io/push-api/#dom-pushsubscription-keys>
    fn Keys(&self, _cx: &mut JSContext) -> DomRoot<PushSubscriptionKeys> {
        DomRoot::from_ref(&*self.keys)
    }
}
