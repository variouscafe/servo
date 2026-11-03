/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

//! namsan 내비게이션 정책 — WebView판 `core/namsan-core/src/nav.rs`와 동일한
//! 규칙을 엔진 안에서 판정한다(CLAUDE.md: 정책은 Rust에서만).
//!
//! 같은 사이트(호스트/서브도메인)만 인앱, 다른 사이트는 외부 위임,
//! mailto/tel/intent는 외부 앱, data:·미지원 스킴은 차단.

use url::Url;

#[derive(Debug, PartialEq)]
pub(crate) enum NavDecision {
    /// 현재 웹뷰(앱) 안에서 계속 진행.
    InApp,
    /// 외부(브라우저/해당 앱)로 위임할 URL.
    External(String),
    /// 조용히 차단.
    Block,
}

/// [source]는 앱의 현재 URL(첫 로드 직후엔 None → 무조건 허용).
pub(crate) fn classify_navigation(source: Option<&Url>, target: &Url) -> NavDecision {
    let scheme = target.scheme();
    match scheme {
        "mailto" | "tel" | "sms" | "intent" | "market" => {
            return NavDecision::External(target.as_str().to_string())
        },
        "http" | "https" => {},
        // about:는 엔진 내부 동작, 나머지(data:, javascript:, 미지원)는 차단.
        "about" => return NavDecision::InApp,
        _ => return NavDecision::Block,
    }

    let Some(source) = source else {
        return NavDecision::InApp;
    };
    if !matches!(source.scheme(), "http" | "https") {
        return NavDecision::InApp;
    }

    if same_site(source.host_str(), target.host_str()) {
        NavDecision::InApp
    } else {
        NavDecision::External(target.as_str().to_string())
    }
}

/// 호스트가 같거나 한쪽이 다른 쪽의 서브도메인이면 같은 사이트로 본다.
fn same_site(a: Option<&str>, b: Option<&str>) -> bool {
    let (Some(a), Some(b)) = (a, b) else {
        return false;
    };
    let (a, b) = (a.to_ascii_lowercase(), b.to_ascii_lowercase());
    a == b || a.ends_with(&format!(".{b}")) || b.ends_with(&format!(".{a}"))
}
