/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use dpi::PhysicalSize;
use euclid::{Rect, Scale};
use keyboard_types::{CompositionEvent, CompositionState, Key, KeyState, NamedKey};
use log::{info, warn};
use raw_window_handle::{DisplayHandle, RawWindowHandle, WindowHandle};
use servo::{
    DeviceIndependentIntRect, DeviceIndependentPixel, DeviceIntSize, DevicePixel, DevicePoint,
    DeviceVector2D, EmbedderControl, EmbedderControlId, EventLoopWaker, ImeEvent, InputEvent,
    KeyboardEvent, LoadStatus, MouseButton, MouseButtonAction, MouseButtonEvent, MouseMoveEvent,
    NavigationRequest, Notification, Opts, PermissionFeature, PermissionRequest, Preferences,
    PushSubscriptionData,
    RefreshDriver, RenderingContext, ScreenGeometry, Scroll, Servo, ServoBuilder, SimpleDialog,
    TouchEvent, TouchEventType, TouchId, TouchPointerType, UserContentManager, WebView, WebViewId,
    WindowRenderingContext, convert_rect_to_css_pixel,
};
use url::Url;

use crate::egl::host_trait::HostTrait;
use crate::egl::namsan_nav;
use crate::prefs::ServoShellPreferences;
use crate::running_app_state::{RunningAppState, UserInterfaceCommand};
use crate::window::NAMSAN_RESIDENT_WEBVIEWS;
use crate::window::{
    PlatformWindow, ServoShellWindow, ServoShellWindowId, TopLevelWebViewCreationRequest,
};

pub(crate) struct EmbeddedPlatformWindow {
    host: Rc<dyn HostTrait>,
    rendering_context: Rc<WindowRenderingContext>,
    refresh_driver: Rc<VsyncRefreshDriver>,
    viewport_rect: RefCell<Rect<i32, DevicePixel>>,
    /// The HiDPI scaling factor to use for the display of [`WebView`]s.
    hidpi_scale_factor: Scale<f32, DeviceIndependentPixel, DevicePixel>,
    /// A list of showing [`InputMethod`] interfaces.
    visible_input_methods: RefCell<Vec<EmbedderControlId>>,
    /// The current title of the active WebView in this window.
    current_title: RefCell<Option<String>>,
    /// The current URL of the active WebView in this window.
    current_url: RefCell<Option<Url>>,
    /// Whether or not the active WebView is currently able to go back.
    current_can_go_back: Cell<bool>,
    /// Whether or not the active WebView is currently able to go forward.
    current_can_go_forward: Cell<bool>,
    /// The current load status of the active WebView.
    current_load_status: Cell<Option<LoadStatus>>,

    id: ServoShellWindowId,
}

impl PlatformWindow for EmbeddedPlatformWindow {
    /// namsan 내비 정책 적용 — 같은 사이트(호스트/서브도메인)만 허용하고
    /// 나머지는 외부 위임(호스트 콜백) 또는 차단. 판정은 전부 Rust 안에서.
    /// 서브프레임(iframe) 내비게이션은 페이지 내부 구성요소이므로 정책 대상이
    /// 아니다 — 차단하면 reCAPTCHA·동영상 임베드 등이 전부 부서진다.
    fn namsan_handle_navigation(&self, source: Option<Url>, request: NavigationRequest) {
        if !request.is_top_level {
            request.allow();
            return;
        }
        match crate::egl::namsan_nav::classify_navigation(source.as_ref(), &request.url) {
            namsan_nav::NavDecision::InApp => request.allow(),
            namsan_nav::NavDecision::External(url) => {
                self.host.on_external_navigation(url);
                request.deny();
            },
            namsan_nav::NavDecision::Block => request.deny(),
        }
    }

    fn as_headed_window(&self) -> Option<&Self> {
        Some(self)
    }

    fn id(&self) -> ServoShellWindowId {
        self.id
    }

    fn screen_geometry(&self) -> ScreenGeometry {
        let viewport_rect = self.viewport_rect.borrow();
        ScreenGeometry {
            size: viewport_rect.size,
            available_size: viewport_rect.size,
            window_rect: viewport_rect.to_box2d(),
        }
    }

    fn device_hidpi_scale_factor(&self) -> Scale<f32, DeviceIndependentPixel, DevicePixel> {
        self.hidpi_scale_factor
    }

    fn hidpi_scale_factor(&self) -> Scale<f32, DeviceIndependentPixel, DevicePixel> {
        self.hidpi_scale_factor
    }

    fn get_fullscreen(&self) -> bool {
        false
    }

    fn update_user_interface_state(
        &self,
        state: &RunningAppState,
        window: &ServoShellWindow,
    ) -> bool {
        let Some(active_webview) = window.active_webview() else {
            return false;
        };

        let new_title = active_webview.page_title();
        let title_changed = new_title != *self.current_title.borrow();
        if title_changed {
            *self.current_title.borrow_mut() = new_title.clone();
            self.host.on_title_changed(new_title);
        }

        let new_url = active_webview.url();
        let url_changed = new_url != *self.current_url.borrow();
        if url_changed {
            let new_url_string = new_url.as_ref().map(Url::to_string).unwrap_or_default();
            *self.current_url.borrow_mut() = new_url;
            if self.has_platform_focus() {
                self.host.on_url_changed(new_url_string);
            }
        }

        let new_back_forward = (
            active_webview.can_go_back(),
            active_webview.can_go_forward(),
        );
        let old_back_forward = (
            self.current_can_go_back.get(),
            self.current_can_go_forward.get(),
        );
        let back_forward_changed = new_back_forward != old_back_forward;
        if back_forward_changed {
            self.current_can_go_back.set(new_back_forward.0);
            self.current_can_go_forward.set(new_back_forward.1);
            self.host
                .on_history_changed(new_back_forward.0, new_back_forward.1);
        }

        let new_load_status = active_webview.load_status();
        let load_status_changed = Some(new_load_status) != self.current_load_status.get();
        if load_status_changed {
            self.host.notify_load_status_changed(new_load_status);
            if state.servoshell_preferences.memory_output && new_load_status == LoadStatus::Complete
            {
                #[cfg(all(feature = "tracing", feature = "tracing-hitrace"))]
                {
                    let (callback, receiver) =
                        servo_base::generic_channel::GenericCallback::new_blocking()
                            .expect("Could not create channel");
                    state.servo().create_memory_report(callback);
                    std::thread::spawn(move || {
                        let result = receiver.recv().expect("Could not get memory report");
                        let reports = result
                            .results
                            .first()
                            .expect("We should have some memory report");
                        let search_string = String::from("resident-according-to-smaps");
                        let sum = reports
                            .reports
                            .iter()
                            .filter(|report| report.path.contains(&search_string))
                            .map(|report| report.size)
                            .sum::<usize>();
                        hitrace::trace_metric_str(
                            "servo_memory_profiling:resident-according-to-smaps/sum",
                            sum as i64,
                        );
                    });
                }
            }
        }

        title_changed || url_changed || back_forward_changed || load_status_changed
    }

    fn request_repaint(&self, window: &ServoShellWindow) {
        window.repaint_webviews();
    }

    fn request_resize(&self, _: &WebView, _: DeviceIntSize) -> Option<DeviceIntSize> {
        None
    }

    fn rendering_context(&self) -> Rc<dyn RenderingContext> {
        self.rendering_context.clone()
    }

    fn window_rect(&self) -> DeviceIndependentIntRect {
        convert_rect_to_css_pixel(
            self.viewport_rect.borrow().to_box2d(),
            self.hidpi_scale_factor(),
        )
    }

    fn show_embedder_control(&self, webview_id: WebViewId, embedder_control: EmbedderControl) {
        let control_id = embedder_control.id();
        match embedder_control {
            EmbedderControl::InputMethod(input_method_control)
                if input_method_control.allow_virtual_keyboard() =>
            {
                self.visible_input_methods.borrow_mut().push(control_id);
                self.host.on_ime_show(input_method_control);
            },
            EmbedderControl::SelectElement(prompt) => {
                self.host.on_show_select_element(webview_id, prompt);
            },
            EmbedderControl::SimpleDialog(SimpleDialog::Alert(alert_dialog)) => {
                self.host.show_alert(alert_dialog.message().into());
                alert_dialog.confirm();
            },
            _ => {}, // The drop implementation will send the default response.
        }
    }

    fn hide_embedder_control(&self, _: WebViewId, control_id: servo::EmbedderControlId) {
        let mut visible_input_methods = self.visible_input_methods.borrow_mut();
        if let Some(index) = visible_input_methods
            .iter()
            .position(|visible_id| *visible_id == control_id)
        {
            visible_input_methods.remove(index);
            self.host.on_ime_hide();
        }
    }

    /// namsan: Notification API — HostTrait(→ JNI → 앱)으로 전달.
    fn show_notification(&self, notification: Notification) {
        self.host.show_notification(notification);
    }

    /// namsan push bridge: 구독 생성/조회를 앱에 위임한다.
    fn request_push_subscription(
        &self,
        origin: String,
        create: bool,
    ) -> Option<PushSubscriptionData> {
        self.host.request_push_subscription(origin, create)
    }

    /// namsan push bridge: 백그라운드 구독 작업 핸들(GL 스레드 블로킹 방지).
    fn push_subscription_worker(
        &self,
    ) -> Option<std::sync::Arc<dyn Fn(String, bool) -> Option<PushSubscriptionData> + Send + Sync>>
    {
        self.host.push_subscription_worker()
    }

    /// namsan: 알림 권한은 앱 차원 정책(앱 설정 + 시스템 권한 상태)으로 즉시 판정하고,
    /// 그 외 권한 요청은 기본 동작(거부)을 유지한다.
    fn show_permission_dialog(&self, _: WebViewId, permission_request: PermissionRequest) {
        match permission_request.feature() {
            PermissionFeature::Notifications => {
                if self.host.request_notification_permission() {
                    permission_request.allow();
                } else {
                    permission_request.deny();
                }
            },
            _ => permission_request.deny(),
        }
    }

    fn notify_crashed(&self, _webview: WebView, reason: String, backtrace: Option<String>) {
        self.host.on_panic(reason, backtrace);
    }

    fn show_console_message(&self, level: servo::ConsoleLogLevel, message: &str) {
        log::log!(level.into(), "{message}");
    }
}

pub(crate) struct VsyncRefreshDriver {
    start_frame_callbacks: RefCell<Vec<Box<dyn Fn() + Send>>>,
    /// On OHOS we own the `OH_NativeVSync` handle and request a single callback
    /// only when an observer asks for one.
    #[cfg(target_env = "ohos")]
    native_vsync: ohos_vsync::NativeVsync,
}

impl VsyncRefreshDriver {
    pub(crate) fn new() -> Self {
        Self {
            start_frame_callbacks: Default::default(),
            #[cfg(target_env = "ohos")]
            native_vsync: ohos_vsync::NativeVsync::new("ServoVsync")
                .expect("Failed to create NativeVsync"),
        }
    }

    fn notify_vsync(&self) {
        let start_frame_callbacks: Vec<_> =
            self.start_frame_callbacks.borrow_mut().drain(..).collect();
        for start_frame_callback in start_frame_callbacks {
            start_frame_callback()
        }
    }
}

impl RefreshDriver for VsyncRefreshDriver {
    fn observe_next_frame(&self, new_start_frame_callback: Box<dyn Fn() + Send + 'static>) {
        // We only need to request a vsync callback if the queue is empty,
        // otherwise we will already have a pending callback.
        #[cfg_attr(not(target_env = "ohos"), allow(unused_variables))]
        let was_empty = {
            let mut callbacks = self.start_frame_callbacks.borrow_mut();
            let was_empty = callbacks.is_empty();
            callbacks.push(new_start_frame_callback);
            was_empty
        };
        #[cfg(target_env = "ohos")]
        if was_empty {
            super::ohos::request_vsync_callback(&self.native_vsync);
        }
    }
}

pub(crate) struct AppInitOptions {
    pub host: Rc<dyn HostTrait>,
    pub event_loop_waker: Box<dyn EventLoopWaker>,
    pub initial_url: Option<String>,
    pub opts: Opts,
    pub preferences: Preferences,
    pub servoshell_preferences: ServoShellPreferences,
    #[cfg(feature = "webxr")]
    pub xr_discovery: Option<servo::webxr::Discovery>,
}

pub struct App {
    pub(crate) state: Rc<RunningAppState>,
    // TODO: multi-window support, like desktop version.
    // This is just an intermediate state, to split refactoring into
    // multiple PRs.
    host: Rc<dyn HostTrait>,
    initial_url: Url,
}

#[expect(unused)]
impl App {
    #[servo::servo_tracing::instrument(skip_all, name = "App::new", level = "info")]
    pub(super) fn new(init: AppInitOptions) -> Rc<Self> {
        let mut servo_builder = ServoBuilder::default()
            .opts(init.opts)
            .preferences(init.preferences.clone())
            .event_loop_waker(init.event_loop_waker.clone());
        let servo = servo_builder.build();
        #[cfg(feature = "webxr")]
        servo.register_webxr_registry(Box::new(XrDiscoveryWebXrRegistry::new(init.xr_discovery)));

        let initial_url = init.initial_url.and_then(|string| Url::parse(&string).ok());
        let initial_url = initial_url
            .or_else(|| Url::parse(&init.servoshell_preferences.homepage).ok())
            .or_else(|| Url::parse("about:blank").ok())
            .expect("Failed to parse initial URL");

        let user_content_manager = Rc::new(UserContentManager::new(&servo));
        let state = Rc::new(RunningAppState::new(
            servo,
            init.servoshell_preferences,
            init.event_loop_waker,
            user_content_manager,
            init.preferences,
        ));

        Rc::new(Self {
            state,
            host: init.host,
            initial_url,
        })
    }

    pub(crate) fn add_platform_window(
        &self,
        display_handle: DisplayHandle,
        window_handle: WindowHandle,
        viewport_rect: Rect<i32, DevicePixel>,
        hidpi_scale_factor: Scale<f32, DeviceIndependentPixel, DevicePixel>,
        window_id: Option<ServoShellWindowId>,
    ) {
        let viewport_size = viewport_rect.size;
        let refresh_driver = Rc::new(VsyncRefreshDriver::new());
        let rendering_context = Rc::new(
            WindowRenderingContext::new_with_refresh_driver(
                display_handle,
                window_handle,
                PhysicalSize::new(viewport_size.width as u32, viewport_size.height as u32),
                refresh_driver.clone(),
            )
            .expect("Could not create RenderingContext"),
        );
        let id = window_id.unwrap_or(ServoShellWindowId::next());
        let platform_window = Rc::new(EmbeddedPlatformWindow {
            id,
            host: self.host.clone(),
            rendering_context,
            refresh_driver,
            viewport_rect: RefCell::new(viewport_rect),
            hidpi_scale_factor,
            visible_input_methods: Default::default(),
            current_title: Default::default(),
            current_url: Default::default(),
            current_can_go_back: Default::default(),
            current_can_go_forward: Default::default(),
            current_load_status: Default::default(),
        });
        self.state.open_window(
            platform_window,
            TopLevelWebViewCreationRequest::WithUrl(self.initial_url.clone()),
        );
    }

    pub(crate) fn servo(&self) -> &Servo {
        &self.state.servo
    }

    pub(crate) fn servoshell_preferences(&self) -> &ServoShellPreferences {
        &self.state.servoshell_preferences
    }

    pub(crate) fn window(&self) -> Rc<ServoShellWindow> {
        self.state
            .focused_window()
            .expect("There is always an active window")
    }

    pub(crate) fn active_or_newest_webview(&self) -> Option<WebView> {
        self.window().active_or_newest_webview()
    }

    pub(crate) fn initial_url(&self) -> Url {
        self.initial_url.clone()
    }

    pub(crate) fn create_and_activate_toplevel_webview(self: &Rc<Self>, url: Url) -> WebView {
        self.window().create_and_activate_toplevel_webview(
            self.state.clone(),
            TopLevelWebViewCreationRequest::WithUrl(url),
        )
    }

    /// The active webview will be immediately valid via `active_or_newest_webview()`
    pub(crate) fn activate_webview(&self, id: WebViewId) {
        let Some(webview) = self.state.webview_by_id(id) else {
            return;
        };
        self.state.window_for_webview(&webview).activate_webview(id);
    }

    /// This is the Servo heartbeat. This needs to be called
    /// everytime wakeup is called or when embedder wants Servo
    /// to act on its pending events.
    pub fn spin_event_loop(&self) {
        if !self
            .state
            .spin_event_loop(None /* create_platform_window */)
        {
            self.host.on_shutdown_complete()
        }
    }

    /// Load an URL.
    pub fn load_uri(&self, location: &str) {
        self.window()
            .queue_user_interface_command(UserInterfaceCommand::Go(location.into()));
        self.spin_event_loop();
    }

    /// namsan: 앱 열기 — 해당 앱의 상주 웹뷰를 활성화한다(재로드 없음).
    /// 없으면 새로 만들고, 상한을 넘으면 오래된 웹뷰부터 정리한다.
    /// 앱 전환 리로드를 제거하는 핵심 경로다.
    pub fn namsan_open_app(&self, app_id: &str, url: &str) {
        let window = self.window();

        // 0) 미추적 웹뷰(window.open·target=_blank) 정리 — 전환 UI가 앱 단위라
        //    다시 띄울 방법이 없는 웹뷰는 좀비가 된다. 활성 웹뷰와 앱에 배정된
        //    웹뷰만 남긴다(close_webview가 앱 매핑도 정리한다).
        let active_now = window.active_or_newest_webview().map(|webview| webview.id());
        let tracked = window.namsan_app_ids();
        for id in window.webview_ids() {
            if Some(id) != active_now && !tracked.contains(&id) {
                window.close_webview(id);
            }
        }

        // 1) 이미 상주하는 앱 웹뷰 → 활성화만. 비활성 사이에 바뀐 뷰포트 크기도
        //    다시 적용해 준다. 방어형 activate — 엔트리 정합성은 close에서 지키지만
        //    이중 안전장치다.
        if let Some(id) = window.namsan_app_webview(app_id) {
            self.activate_webview(id);
            window.namsan_touch_app(app_id, id);
            if let Some(webview) = window.webview_by_id(id) {
                if let Some(embedded) = window.platform_window().as_headed_window() {
                    let viewport = embedded.viewport_rect.borrow().clone();
                    webview.resize(PhysicalSize::new(
                        viewport.size.width as u32,
                        viewport.size.height as u32,
                    ));
                    // 활성화에는 로드 이벤트가 없다 — UI가 스플래시를 걷도록
                    // URL 콜백을 직접 발화한다. 캐시도 갱신해 diff가 같은 값을
                    // 다시 보내는 일이 없게 한다.
                    let url_now = webview.url();
                    *embedded.current_url.borrow_mut() = url_now.clone();
                    embedded.host.on_url_changed(
                        url_now.map(|u| u.to_string()).unwrap_or_default(),
                    );
                }
            }
            self.spin_event_loop();
            return;
        }

        // 2) 첫 앱: 시작 시 만든 미사용 웹뷰를 그대로 가져온다(about:blank 등).
        //    워밍업으로 이미 같은 URL을 로드 중이면 재로드하지 않는다.
        let existing = if window.namsan_apps_is_empty() && window.webview_ids().len() == 1 {
            window.active_or_newest_webview()
        } else {
            None
        };
        if let Some(webview) = existing {
            let id = webview.id();
            // 정규화된 Url 객체끼리 비교(문자열 직렬화 차이로 웜 판정을 놓치지 않게).
            let warm = webview
                .url()
                .is_some_and(|u| Url::parse(url).is_ok_and(|parsed| parsed == u));
            window.activate_webview(id);
            if !warm {
                window.queue_user_interface_command(UserInterfaceCommand::Go(url.to_owned()));
            } else if let Some(embedded) = window.platform_window().as_headed_window() {
                // 웜 시작은 로드 이벤트가 없다 — 위와 같은 이유로 직접 알린다.
                *embedded.current_url.borrow_mut() = webview.url();
                embedded.host.on_url_changed(url.to_owned());
            }
            window.namsan_touch_app(app_id, id);
            self.spin_event_loop();
            return;
        }

        // 3) 새 웹뷰 생성 + 상주 목록 등록 + 상한 초과분 정리.
        let Ok(parsed) = Url::parse(url) else {
            warn!("namsan_open_app: URL 파싱 실패 — {url}");
            // 실패해도 스플래시가 영구 걸리지 않게 UI 갱신 신호는 보낸다.
            if let Some(embedded) = window.platform_window().as_headed_window() {
                embedded.host.on_url_changed(url.to_owned());
            }
            return;
        };
        let webview = self.window().create_and_activate_toplevel_webview(
            self.state.clone(),
            TopLevelWebViewCreationRequest::WithUrl(parsed),
        );
        let id = webview.id();
        window.namsan_touch_app(app_id, id);
        window.namsan_trim_apps(NAMSAN_RESIDENT_WEBVIEWS, id);
        self.spin_event_loop();
    }

    /// Reload the page.
    pub fn reload(&self) {
        self.window()
            .queue_user_interface_command(UserInterfaceCommand::Reload);
        self.spin_event_loop();
    }

    /// Stop loading the page.
    pub fn stop(&self) {
        warn!("TODO can't stop won't stop");
    }

    /// Go back in history.
    pub fn go_back(&self) {
        self.window()
            .queue_user_interface_command(UserInterfaceCommand::Back);
        self.spin_event_loop();
    }

    /// Go forward in history.
    pub fn go_forward(&self) {
        self.window()
            .queue_user_interface_command(UserInterfaceCommand::Forward);
        self.spin_event_loop();
    }

    /// Let Servo know that the window has been resized.
    pub fn resize(&self, viewport_rect: Rect<i32, DevicePixel>) {
        if let Some(webview) = self.active_or_newest_webview() {
            info!("Setting viewport to {viewport_rect:?}");
            let size = viewport_rect.size;
            webview.resize(PhysicalSize::new(size.width as u32, size.height as u32));
        }
        let window = self.window().platform_window();
        let embedded_platform_window = window.as_headed_window().expect("No headed window");
        *embedded_platform_window.viewport_rect.borrow_mut() = viewport_rect;

        self.spin_event_loop();
    }

    /// Scroll.
    /// x/y are scroll coordinates.
    /// dx/dy are scroll deltas.
    pub fn scroll(&self, dx: f32, dy: f32, x: f32, y: f32) {
        if let Some(webview) = self.active_or_newest_webview() {
            let scroll = Scroll::Delta(DeviceVector2D::new(dx, dy).into());
            let point = DevicePoint::new(x, y).into();
            webview.notify_scroll_event(scroll, point);
            self.spin_event_loop();
        }
    }

    /// Touch event: press down
    pub fn touch_down(&self, x: f32, y: f32, pointer_id: i32) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::Touch(TouchEvent::new(
                TouchEventType::Down,
                TouchId(pointer_id),
                DevicePoint::new(x, y).into(),
                TouchPointerType::Touch,
            )));
            self.spin_event_loop();
        }
    }

    /// Touch event: move touching finger
    pub fn touch_move(&self, x: f32, y: f32, pointer_id: i32) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::Touch(TouchEvent::new(
                TouchEventType::Move,
                TouchId(pointer_id),
                DevicePoint::new(x, y).into(),
                TouchPointerType::Touch,
            )));
            self.spin_event_loop();
        }
    }

    /// Touch event: Lift touching finger
    pub fn touch_up(&self, x: f32, y: f32, pointer_id: i32) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::Touch(TouchEvent::new(
                TouchEventType::Up,
                TouchId(pointer_id),
                DevicePoint::new(x, y).into(),
                TouchPointerType::Touch,
            )));
            self.spin_event_loop();
        }
    }

    /// Cancel touch event
    pub fn touch_cancel(&self, x: f32, y: f32, pointer_id: i32) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::Touch(TouchEvent::new(
                TouchEventType::Cancel,
                TouchId(pointer_id),
                DevicePoint::new(x, y).into(),
                TouchPointerType::Touch,
            )));
            self.spin_event_loop();
        }
    }

    /// Register a mouse movement.
    pub fn mouse_move(&self, x: f32, y: f32) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::MouseMove(MouseMoveEvent::new(
                DevicePoint::new(x, y).into(),
            )));
            self.spin_event_loop();
        }
    }

    /// Register a mouse button press.
    pub fn mouse_down(&self, x: f32, y: f32, button: MouseButton) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::MouseButton(MouseButtonEvent::new(
                MouseButtonAction::Down,
                button,
                DevicePoint::new(x, y).into(),
            )));
            self.spin_event_loop();
        }
    }

    /// Register a mouse button release.
    pub fn mouse_up(&self, x: f32, y: f32, button: MouseButton) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::MouseButton(MouseButtonEvent::new(
                MouseButtonAction::Up,
                button,
                DevicePoint::new(x, y).into(),
            )));
            self.spin_event_loop();
        }
    }

    /// Start pinchzoom.
    /// x/y are pinch origin coordinates.
    pub fn pinchzoom_start(&self, factor: f32, x: f32, y: f32) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.adjust_pinch_zoom(factor, DevicePoint::new(x, y));
            self.spin_event_loop();
        }
    }

    /// Pinchzoom.
    /// x/y are pinch origin coordinates.
    pub fn pinchzoom(&self, factor: f32, x: f32, y: f32) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.adjust_pinch_zoom(factor, DevicePoint::new(x, y));
            self.spin_event_loop();
        }
    }

    /// End pinchzoom.
    /// x/y are pinch origin coordinates.
    pub fn pinchzoom_end(&self, factor: f32, x: f32, y: f32) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.adjust_pinch_zoom(factor, DevicePoint::new(x, y));
            self.spin_event_loop();
        }
    }

    pub fn key_down(&self, key: Key) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::Keyboard(KeyboardEvent::from_state_and_key(
                KeyState::Down,
                key,
            )));
            self.spin_event_loop();
        }
    }

    pub fn key_up(&self, key: Key) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::Keyboard(KeyboardEvent::from_state_and_key(
                KeyState::Up,
                key,
            )));
            self.spin_event_loop();
        }
    }

    pub fn key_event(&self, event: keyboard_types::KeyboardEvent) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::Keyboard(KeyboardEvent::new(event)));
            self.spin_event_loop();
        }
    }

    pub fn ime_insert_text(&self, text: String) {
        // In OHOS, we get empty text after the intended text.
        if text.is_empty() {
            return;
        }

        let Some(webview) = self.active_or_newest_webview() else {
            return;
        };

        webview.notify_input_event(InputEvent::Keyboard(KeyboardEvent::from_state_and_key(
            KeyState::Down,
            Key::Named(NamedKey::Process),
        )));
        webview.notify_input_event(InputEvent::Ime(ImeEvent::Composition(CompositionEvent {
            state: CompositionState::End,
            data: text,
        })));
        webview.notify_input_event(InputEvent::Keyboard(KeyboardEvent::from_state_and_key(
            KeyState::Up,
            Key::Named(NamedKey::Process),
        )));
        self.spin_event_loop();
    }

    pub fn ime_dismissed(&self) {
        if let Some(webview) = self.active_or_newest_webview() {
            webview.notify_input_event(InputEvent::Ime(ImeEvent::Dismissed));
        }
        self.host.on_ime_hide();
        self.spin_event_loop();
    }

    pub fn notify_vsync(&self) {
        let platform_window = self.window().platform_window();
        let embedded_platform_window = platform_window
            .as_headed_window()
            .expect("No headed window");
        embedded_platform_window.refresh_driver.notify_vsync();
        self.spin_event_loop();
    }

    pub fn pause_painting(&self) {
        let platform_window = self.window().platform_window();
        let embedded_platform_window = platform_window
            .as_headed_window()
            .expect("No headed window");
        if let Err(error) = embedded_platform_window.rendering_context.take_window() {
            warn!("Unbinding native surface from context failed ({error:?})");
        }
        self.spin_event_loop();
    }

    pub fn resume_painting(
        &self,
        window_handle: RawWindowHandle,
        viewport_rect: Rect<i32, DevicePixel>,
    ) {
        let window_handle = unsafe { WindowHandle::borrow_raw(window_handle) };
        let size = viewport_rect.size.to_u32();
        let platform_window = self.window().platform_window();
        let embedded_platform_window = platform_window
            .as_headed_window()
            .expect("No headed window");
        if let Err(error) = embedded_platform_window
            .rendering_context
            .set_window(window_handle, PhysicalSize::new(size.width, size.height))
        {
            warn!("Binding native surface to context failed ({error:?})");
        }
        embedded_platform_window.request_repaint(&self.window());
        self.spin_event_loop();
    }
}

#[cfg(feature = "webxr")]
pub(crate) struct XrDiscoveryWebXrRegistry {
    xr_discovery: RefCell<Option<servo::webxr::Discovery>>,
}

#[cfg(feature = "webxr")]
impl XrDiscoveryWebXrRegistry {
    pub(crate) fn new(xr_discovery: Option<servo::webxr::Discovery>) -> Self {
        Self {
            xr_discovery: RefCell::new(xr_discovery),
        }
    }
}

#[cfg(feature = "webxr")]
impl servo::webxr::WebXrRegistry for XrDiscoveryWebXrRegistry {
    fn register(&self, registry: &mut servo::webxr::MainThreadRegistry) {
        log::debug!("XrDiscoveryWebXrRegistry::register");
        if let Some(discovery) = self.xr_discovery.take() {
            registry.register(discovery);
        }
    }
}
