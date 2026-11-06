/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import org.servo.servoshell.ui.home.UiApp
import org.servo.servoshell.ui.home.NamsanHome
import org.servo.servoshell.ui.home.toUiApp
import org.servo.servoshell.ui.theme.NamsanTheme
import org.servo.servoshell.ui.webapp.AppDrawerPanel
import org.servo.servoshell.ui.webapp.EdgeSwipeZone
import org.servo.servoview.Servo
import org.servo.servoview.ServoNavigator

/**
 * 남산 런처(servo 엔진). 홈은 앱 아이콘 그리드, "+"로 사이트 추가,
 * 앱 화면은 전체 화면 실행 — URL 주소창은 어디에도 없다.
 * 좌측 엣지(24dp) 우측 스와이프 → 앱 목록 패널.
 */
class MainActivity : ComponentActivity(), Servo.Client {
    private var currentApp by mutableStateOf<UiApp?>(null)
    private var apps by mutableStateOf<List<UiApp>>(emptyList())
    private var loading by mutableStateOf(true)
    private var adding by mutableStateOf(false)
    private var drawerOpen by mutableStateOf(false)
    private var alertMessage by mutableStateOf<String?>(null)
    private var isRefreshing by mutableStateOf(false)
    private var splashActive by mutableStateOf(false)
    private var softKeyboardVisible by mutableStateOf(false)

    private var currentUrl = ""
    private var currentTitle = ""
    private lateinit var navigator: ServoNavigator
    private lateinit var servo: Servo
    private lateinit var repository: org.servo.servoshell.data.WebAppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // 외부 저장소 일시 불가 시 null 반환이 가능하다 — !! 크래시 대신
        // 내부 filesDir로 폴백(리뷰 반영, 크래시 루프 방지).
        val sdcard = getExternalFilesDir("") ?: filesDir
        val host = sdcard.toPath().resolve("android_hosts").toString()
        try {
            Os.setenv("HOST_FILE", host, false)
            // namsan 진단 릴레이(NAMSAN_CONSOLE_RELAY=1)는 정식 빌드에서 끈다.
            // 콘솔·패닉을 VPS로 보내는 진단 전용 경로 — 진단 세션이 필요하면 이
            // 줄을 다시 활성화. NAMSAN_NO_WEBGL=1 토글도 엔진측에 남아 있다.
        } catch (e: ErrnoException) {
            e.printStackTrace()
        }

        super.onCreate(savedInstanceState)
        repository = (application as NamsanApp).repository
        navigator = ServoNavigator()

        // 알림 탭 등 외부 URL 진입은 그대로 앱 화면으로 연다.
        val initialUrl = if (Intent.ACTION_VIEW == intent.action) intent.data?.toString() else null
        currentApp = initialUrl?.toExternalApp()

        var experimentalModeEnabled by mutableStateOf(loadExperimentalPreference())
        servo = Servo(
            args = intent.getStringExtra("servoargs"),
            url = initialUrl,
            logStr = intent.getStringExtra("servolog"),
            experimentalMode = experimentalModeEnabled,
            scope = lifecycleScope,
            client = this,
            context = this,
            navigator = navigator,
        )

        refreshApps()

        // namsan: Android 13+ 알림 런타임 권한 — 웹 노티 표시 조건.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                experimentalModeEnabled = loadExperimentalPreference()
            }
            NamsanTheme {
                NamsanRoot(experimentalModeEnabled)
            }
        }
    }

    // ── 화면 구성 ────────────────────────────────────────────

    @Composable
    private fun NamsanRoot(experimentalModeEnabled: Boolean) {
        val app = currentApp

        // 뒤로: (우선순위 역순 등록) 앱 화면 → 사이트 뒤로/홈 복귀, 드로어 열림 → 닫기.
        BackHandler(enabled = app != null) {
            if (navigator.canGoBack) navigator.back() else currentApp = null
        }
        // 드로어 드래그 추적 — 손가락을 따라 열리고, 놓으면 결정된 방향으로 스르륵.
        val drawerProgress = remember { Animatable(0f) }
        val drawerScope = rememberCoroutineScope()
        val drawerHaptics = LocalHapticFeedback.current
        val panelWidthPx = with(LocalDensity.current) { 300.dp.toPx() }
        fun dragDrawer(deltaPx: Float) {
            drawerScope.launch {
                drawerProgress.snapTo(
                    (drawerProgress.value + deltaPx / panelWidthPx).coerceIn(0f, 1f),
                )
            }
        }
        fun settleDrawer() {
            drawerScope.launch {
                // 같은 디스패처 큐에 쌓인 snapTo들이 모두 실행된 뒤의 값을 읽는다
                // (빠른 플릭에서 마지막 델타 반영 전에 읽어 방향을 오판하는 것 방지).
                val target = if (drawerProgress.value > 0.4f) 1f else 0f
                drawerProgress.animateTo(target, tween(220))
                drawerOpen = target == 1f
                // 열림으로 확정된 순간 가벼운 햅틱 — 제스처 마감 확인감.
                if (target == 1f) {
                    drawerHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
        fun closeDrawer() {
            drawerOpen = false
            drawerScope.launch { drawerProgress.animateTo(0f, tween(180)) }
        }

        BackHandler(enabled = drawerOpen) { closeDrawer() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            // 상단바와 로드 진행바는 앱 화면에서만 나타난다(부드러운 슬라이드).
            AnimatedVisibility(visible = app != null, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                Column {
                    app?.let { NamsanTopBar(app = it, refreshing = isRefreshing) }
                    // Chrome 스타일 얇은 진행바
                    AnimatedVisibility(visible = isRefreshing) {
                        LinearProgressIndicator(
                            progress = { 0.7f },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 서보 표면 — 항상 compose 유지(onRelease가 glDispatcher를 닫아 재사용 불가).
                // 홈일 때는 불투명 홈 화면이 위를 덮는다.
                Servo(
                    experimentalModeEnabled = experimentalModeEnabled,
                    softKeyboardVisible = softKeyboardVisible,
                    servo = servo,
                    navigator = navigator,
                    modifier = Modifier.fillMaxSize(),
                )

                // 홈 ↔ 앱 전환: 홈이 부드럽게 사라지며 서보 표면이 드러난다.
                androidx.compose.animation.AnimatedVisibility(
                    visible = app == null,
                    enter = fadeIn(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(180)) +
                        slideOutVertically(animationSpec = tween(220)) { it / 12 },
                ) {
                    NamsanHome(
                        apps = apps,
                        loading = loading,
                        adding = adding,
                        iconLoader = { id -> repository.icon(id) },
                        onOpenApp = { target -> openApp(target) },
                        onRename = { target, name -> renameApp(target, name) },
                        onDelete = { target -> deleteApp(target) },
                        onAdd = { input, done -> submitAdd(input, done) },
                    )
                }

                // 앱 첫 로드 스플래시 — 흰 플래시 대신 브랜드 오버레이.
                androidx.compose.animation.AnimatedVisibility(visible = splashActive, exit = fadeOut(tween(200))) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painterResource(R.drawable.namsan_splash_mark),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(72.dp),
                            )
                            Spacer(Modifier.height(20.dp))
                            CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp))
                        }
                    }
                }

                // 좌측 엣지 그래버 — 드로어를 열 수 있다는 시각적 단서(앱 화면에서만).
                if (app != null && !drawerOpen) {
                    Box(
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .width(4.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                            )
                            .align(Alignment.CenterStart),
                    )
                }

                // 엣지 스와이프 드로어 — 홈/앱 화면 공통(앱 전환의 일관된 진입점).
                EdgeSwipeZone(
                    onDragDelta = { delta -> dragDrawer(delta) },
                    onRelease = { settleDrawer() },
                )
                AppDrawerPanel(
                    progress = drawerProgress,
                    apps = apps,
                    currentAppId = app?.id ?: "",
                    iconLoader = { id -> repository.icon(id) },
                    onDismiss = { closeDrawer() },
                    onSelect = { target ->
                        closeDrawer()
                        if (app == null || target.id != app.id) openApp(target)
                    },
                    onHome = { closeDrawer(); currentApp = null },
                    onAdd = { closeDrawer(); currentApp = null }, // 홈의 +로 유도
                    onDragDelta = { delta -> dragDrawer(delta) },
                    onRelease = { settleDrawer() },
                )
            }
        }

        alertMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { alertMessage = null },
                confirmButton = {
                    TextButton(onClick = { alertMessage = null }) { Text("확인") }
                },
                text = { Text(message) },
            )
        }
    }

    /** 상단바: 홈 / 앱 이름 / 메뉴 — URL 입력창은 없다. */
    @Composable
    private fun NamsanTopBar(app: UiApp, refreshing: Boolean) {
        val context = LocalContext.current
        Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { currentApp = null }) {
                    Icon(Icons.Filled.Home, contentDescription = "홈")
                }
                Text(
                    app.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (refreshing) navigator.stop() else navigator.reload() }) {
                    Icon(
                        if (refreshing) Icons.Filled.Close else Icons.Filled.Refresh,
                        contentDescription = if (refreshing) "정지" else "새로고침",
                    )
                }
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "메뉴")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("새로고침") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                        onClick = { menuOpen = false; navigator.reload() },
                    )
                    DropdownMenuItem(
                        text = { Text("공유") },
                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                        onClick = {
                            menuOpen = false
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, currentUrl.ifBlank { app.url })
                                        },
                                        "공유",
                                    ),
                                )
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("브라우저로 열기") },
                        onClick = {
                            menuOpen = false
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl.ifBlank { app.url })),
                                )
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("설정") },
                        leadingIcon = { Icon(Icons.Filled.Settings, null) },
                        onClick = {
                            menuOpen = false
                            startActivity(Intent(context, SettingsActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    // ── 동작 ────────────────────────────────────────────────

    private fun openApp(target: UiApp) {
        currentApp = target
        splashActive = true
        navigator.openApp(target.id, target.url)
        lifecycleScope.launch {
            runCatching { repository.markOpened(target.id) }
        }
    }

    private fun refreshApps() {
        lifecycleScope.launch {
            loading = true
            apps = runCatching {
                repository.listApps()
                    .map { it.toUiApp() }
                    // 최근 사용 순 + 미사용 앱은 등록 순(안정 정렬)으로 뒤에.
                    .let { list ->
                        list.filter { it.lastOpenedAt != null }
                            .sortedByDescending { it.lastOpenedAt!! } +
                            list.filter { it.lastOpenedAt == null }
                    }
            }.getOrDefault(emptyList())
            loading = false
        }
    }

    private fun submitAdd(input: String, done: (Result<Unit>) -> Unit) {
        lifecycleScope.launch {
            adding = true
            val result = runCatching { repository.addApp(input) }.map { }
            adding = false
            if (result.isSuccess) {
                apps = runCatching { repository.listApps().map { it.toUiApp() } }
                .getOrDefault(apps)
                .let { l -> l.filter { it.lastOpenedAt != null }.sortedByDescending { it.lastOpenedAt!! } + l.filter { it.lastOpenedAt == null } }
            }
            done(result)
        }
    }

    private fun renameApp(target: UiApp, name: String) {
        lifecycleScope.launch {
            runCatching { repository.renameApp(target.id, name) }
            refreshApps()
        }
    }

    private fun deleteApp(target: UiApp) {
        lifecycleScope.launch {
            runCatching { repository.deleteApp(target.id) }
            if (currentApp?.id == target.id) currentApp = null
            refreshApps()
        }
    }

    private fun String.toExternalApp(): UiApp {
        val host = runCatching { Uri.parse(this).host }.getOrNull() ?: this
        return UiApp(id = "external:$this", name = host, url = this, themeColor = null)
    }

    private fun loadExperimentalPreference(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean("experimental", false)

    // ── Servo.Client 콜백 ───────────────────────────────────

    override fun onAlert(message: String) {
        alertMessage = message
    }

    override fun onNotification(title: String, body: String) {
        NamsanPush.showWebNotification(this, title, body)
    }

    override fun onRequestNotificationPermission(): Boolean =
        NamsanPush.notificationPermission(this)

    override fun onPushSubscribe(origin: String): String? =
        NamsanPush.subscribeBlocking(origin)

    override fun onPushGetSubscription(origin: String): String? =
        NamsanPush.getSubscription(origin)

    override fun onExternalNavigation(url: String) {
        // 같은 사이트가 아니면 시스템 브라우저/앱으로 위임(판정은 엔진 Rust).
        // 남산이 기본 브라우저여도 자기 자신으로 되돌아가며 무한 증식하지 않도록
        // 자기 패키지는 위임 대상에서 제외한다.
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val target = packageManager.queryIntentActivities(intent, 0)
                .firstOrNull { it.activityInfo?.packageName != packageName }
            if (target != null) {
                startActivity(intent.setPackage(target.activityInfo.packageName))
            } else {
                Toast.makeText(this, "열 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Toast.makeText(this, "열 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLoadStarted() {
        isRefreshing = true
    }

    override fun onLoadEnded() {
        isRefreshing = false
        splashActive = false
    }

    override fun onTitleChanged(title: String) {
        currentTitle = title
    }

    override fun onUrlChanged(url: String) {
        currentUrl = url
        // 상주 웹뷰 활성화(재로드 없는 앱 전환)는 로드 이벤트가 오지 않으므로
        // URL 콜백 시점에 스플래시/진행 상태를 해제한다.
        splashActive = false
        isRefreshing = false
    }

    override fun onImeShow() {
        softKeyboardVisible = true
    }

    override fun onImeHide() {
        softKeyboardVisible = false
    }
}
