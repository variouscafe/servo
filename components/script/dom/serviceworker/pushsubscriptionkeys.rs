/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

//! namsan push bridge: 구독 키 쌍(p256dh/auth) 값 객체. 코드젠이 기대하는
//! 모듈명(인터페이스 소문자)을 따른다.

use dom_struct::dom_struct;
use js::context::JSContext;
use script_bindings::reflector::{Reflector, reflect_dom_object};

use crate::dom::bindings::codegen::Bindings::PushSubscriptionBinding::PushSubscriptionKeysMethods;
use crate::dom::bindings::root::DomRoot;
use crate::dom::bindings::str::DOMString;
use crate::dom::globalscope::GlobalScope;

#[dom_struct]
pub(crate) struct PushSubscriptionKeys {
    reflector_: Reflector,
    /// <https://w3c.github.io/push-api/#dom-pushsubscriptionkeys-p256dh>
    p256dh: DOMString,
    /// <https://w3c.github.io/push-api/#dom-pushsubscriptionkeys-auth>
    auth: DOMString,
}

impl PushSubscriptionKeys {
    fn new_inherited(p256dh: DOMString, auth: DOMString) -> PushSubscriptionKeys {
        PushSubscriptionKeys {
            reflector_: Reflector::new(),
            p256dh,
            auth,
        }
    }

    #[cfg_attr(crown, expect(crown::unrooted_must_root))]
    pub(crate) fn new(
        cx: &mut JSContext,
        global: &GlobalScope,
        p256dh: String,
        auth: String,
    ) -> DomRoot<PushSubscriptionKeys> {
        let keys = PushSubscriptionKeys::new_inherited(DOMString::from(p256dh), DOMString::from(auth));
        reflect_dom_object(cx, Box::new(keys), global)
    }
}

impl PushSubscriptionKeysMethods<crate::DomTypeHolder> for PushSubscriptionKeys {
    /// <https://w3c.github.io/push-api/#dom-pushsubscriptionkeys-p256dh>
    fn P256dh(&self) -> DOMString {
        self.p256dh.clone()
    }

    /// <https://w3c.github.io/push-api/#dom-pushsubscriptionkeys-auth>
    fn Auth(&self) -> DOMString {
        self.auth.clone()
    }
}
