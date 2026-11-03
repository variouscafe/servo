/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell.ui.home

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.servo.servoshell.R
import org.servo.servoshell.ui.icons.tileColor

/** 홈 그리드에 올리는 통일된 앱 표현(레지스트리 항목 또는 외부 URL 진입). */
data class UiApp(
    val id: String,
    val name: String,
    val url: String,
    val themeColor: String?,
    /** 마지막 실행 epoch ms — 최근 사용 순 정렬에 사용(null=미사용). */
    val lastOpenedAt: Long? = null,
)

fun dev.namsan.core.WebAppSummary.toUiApp() =
    UiApp(id = id, name = name, url = url, themeColor = themeColor, lastOpenedAt = lastOpenedAt)

/**
 * 남산 홈 — Launchpad 스타일 앱 아이콘 그리드와 "+" 타일.
 * URL 입력창은 존재하지 않는다(이 앱의 정체성).
 * 롱프레스 → 컨텍스트 메뉴(이름 변경/삭제).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NamsanHome(
    apps: List<UiApp>,
    loading: Boolean,
    adding: Boolean,
    iconLoader: suspend (String) -> Bitmap?,
    onOpenApp: (UiApp) -> Unit,
    onRename: (UiApp, String) -> Unit,
    onDelete: (UiApp) -> Unit,
    onAdd: (String, (Result<Unit>) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<UiApp?>(null) }
    var renameFor by remember { mutableStateOf<UiApp?>(null) }
    var deleteFor by remember { mutableStateOf<UiApp?>(null) }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (apps.isEmpty() && !loading) {
            Column(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painterResource(R.drawable.namsan_splash_mark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "웹앱을 추가해 남산을 시작하세요",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { showAdd = true }) { Text("웹사이트 추가") }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(apps, key = { it.id }) { app ->
                    Box(Modifier.animateItem()) {
                        AppTile(
                            app = app,
                            iconLoader = iconLoader,
                            onClick = { onOpenApp(app) },
                            onLongClick = { menuFor = app },
                        )
                    }
                }
                item(key = "add") {
                    Box(Modifier.animateItem()) { AddTile(onClick = { showAdd = true }) }
                }
            }
            if (loading && apps.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    // 롱프레스 컨텍스트 메뉴
    menuFor?.let { app ->
        Box(Modifier.fillMaxSize()) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuFor = null },
                modifier = Modifier.padding(8.dp),
            ) {
                DropdownMenuItem(
                    text = { Text("이름 변경") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menuFor = null; renameFor = app },
                )
                DropdownMenuItem(
                    text = { Text("삭제") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menuFor = null; deleteFor = app },
                )
            }
        }
    }

    if (showAdd) {
        AddAppDialog(
            busy = adding,
            onDismiss = { showAdd = false },
            onSubmit = onAdd,
            onSuccess = { showAdd = false },
        )
    }

    renameFor?.let { app ->
        RenameDialog(
            name = app.name,
            onDismiss = { renameFor = null },
            onConfirm = { newName ->
                renameFor = null
                onRename(app, newName)
            },
        )
    }

    deleteFor?.let { app ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("앱 삭제") },
            text = { Text("\"${app.name}\"를 홈 화면에서 제거할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    onDelete(app)
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text("취소") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: UiApp,
    iconLoader: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, app.id) { value = iconLoader(app.id) }
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tileScale",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 2.dp),
    ) {
        val bg = tileColor(app)
        Box(
            modifier = Modifier
                .size(58.dp)
                .shadow(2.dp, RoundedCornerShape(15.dp))
                .clip(RoundedCornerShape(15.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize())
            } else {
                Text(
                    app.name.take(1),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddTile(onClick: () -> Unit) {
    // AppTile과 동일한 눌림 피드백 — 재질이 달라도 조작감은 하나로.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "addTileScale",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 2.dp),
    ) {
        Surface(
            modifier = Modifier.size(58.dp),
            shape = RoundedCornerShape(15.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "웹사이트 추가",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("추가", style = MaterialTheme.typography.bodySmall)
    }
}
