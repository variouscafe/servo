/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

use servo::{
    InputMethodControl, LoadStatus, Notification, PushSubscriptionData, SelectElement, WebViewId,
};

/// Callbacks implemented by embedder. Called by our RunningAppState, generally on behalf of Servo.
pub trait HostTrait {
    fn show_alert(&self, message: String);
    /// namsan: 표시할 웹 알림(Notification API). 기본 구현은 무시한다.
    fn show_notification(&self, _notification: Notification) {}
    /// namsan: 정책상 외부로 위임할 내비게이션(앱이 시스템 브라우저/앱으로 연다).
    fn on_external_navigation(&self, _url: String) {}
    /// namsan: 페이지가 알림 권한을 요청했다. 앱 설정·시스템 권한 상태로 즉시 판정한다.
    fn request_notification_permission(&self) -> bool {
        false
    }
    /// namsan push bridge: 해당 origin의 Web Push 구독 생성(create)/조회.
    /// 조회에서 없으면 `None` — 새로 만들지 않는다. 생성 경로의 `None`은
    /// 구독 실패(페이지의 프라미스 거부).
    fn request_push_subscription(&self, _origin: String, _create: bool) -> Option<PushSubscriptionData> {
        None
    }
    /// namsan push bridge: 임의 스레드에서 구독 생성/조회를 수행할 수 있는
    /// Send 작업 핸들(안드로이드 JNI — 릴레이 등록 I/O가 GL 스레드를 멈추지
    /// 않게 한다). None이면 동기 경로로 폴백.
    fn push_subscription_worker(
        &self,
    ) -> Option<std::sync::Arc<dyn Fn(String, bool) -> Option<PushSubscriptionData> + Send + Sync>>
    {
        None
    }
    /// Notify that the load status of the page has changed.
    ///
    /// Started:
    ///  - "Reload button" should be disabled.
    ///  - "Stop button" should be enabled.
    ///  - Throbber starts spinning.
    ///
    /// Complete:
    ///  - "Reload button" should be enabled.
    ///  - "Stop button" should be disabled.
    ///  - Throbber stops spinning.
    fn notify_load_status_changed(&self, load_status: LoadStatus);
    /// Page title has changed.
    fn on_title_changed(&self, title: Option<String>);
    /// Page URL has changed.
    fn on_url_changed(&self, url: String);
    /// Back/forward state has changed.
    /// Back/forward buttons need to be disabled/enabled.
    fn on_history_changed(&self, can_go_back: bool, can_go_forward: bool);
    /// Servo finished shutting down.
    fn on_shutdown_complete(&self);
    /// A text input is focused.
    fn on_ime_show(&self, input_method_control: InputMethodControl);
    /// Input lost focus
    fn on_ime_hide(&self);
    /// Called when a `<select>` element is clicked.
    fn on_show_select_element(&self, webview_id: WebViewId, prompt: SelectElement);
    /// Called when we get a panic message from constellation
    fn on_panic(&self, reason: String, backtrace: Option<String>);
}
