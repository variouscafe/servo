package org.servo.servoshell.ui.webapp

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.servo.servoshell.ui.home.UiApp
import org.servo.servoshell.ui.icons.tileColor

/**
 * 좌측 엣지 스와이프로 열리는 앱 전환 패널 — 손가락을 따라 열리고(진행도 0..1),
 * 패널이나 스크림을 좌측으로 드래그해 직접 닫을 수도 있다.
 */
@Composable
fun AppDrawerPanel(
    progress: Animatable<Float, AnimationVector1D>,
    apps: List<UiApp>,
    currentAppId: String,
    iconLoader: suspend (String) -> Bitmap?,
    onDismiss: () -> Unit,
    onSelect: (UiApp) -> Unit,
    onHome: () -> Unit,
    onAdd: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    val value = progress.value
    if (value <= 0.001f) return

    Box(modifier = Modifier.fillMaxSize()) {
        // 스크림 — 진행도에 비례해 어두워진다.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.4f * value }
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { onRelease() },
                        onDragCancel = { onRelease() },
                    ) { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount)
                    }
                },
        )
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp)
                .graphicsLayer { translationX = -size.width * (1f - value) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { onRelease() },
                        onDragCancel = { onRelease() },
                    ) { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount)
                    }
                },
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        ) {
            Column(Modifier.systemBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("앱", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onHome) {
                        Icon(Icons.Filled.Home, contentDescription = "홈 화면")
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(apps, key = { it.id }) { rowApp ->
                        val isCurrent = rowApp.id == currentAppId
                        CurrentAppHighlight(current = isCurrent) {
                            DrawerAppRow(
                                app = rowApp,
                                current = isCurrent,
                                iconLoader = iconLoader,
                                onClick = { onSelect(rowApp) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAdd)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("웹사이트 추가")
                }
            }
        }
    }
}

@Composable
private fun DrawerAppRow(
    app: UiApp,
    current: Boolean,
    iconLoader: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, app.id) { value = iconLoader(app.id) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 홈 타일과 동일한 색 규칙(테마색 우선, 해시 팔레트 폴백) — 위치 무관 일관성.
        val bg = tileColor(app)
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.size(36.dp))
            else Text(app.name.take(1), color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            app.name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (current) {
            Icon(Icons.Filled.Check, contentDescription = "현재 앱", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 현재 앱 행 전체를 은은하게 강조하는 배경 래퍼. */
@Composable
private fun CurrentAppHighlight(current: Boolean, content: @Composable () -> Unit) {
    if (current) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) { content() }
    } else {
        content()
    }
}
