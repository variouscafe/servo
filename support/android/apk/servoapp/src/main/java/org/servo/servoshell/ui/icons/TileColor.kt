/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell.ui.icons

import androidx.compose.ui.graphics.Color
import org.servo.servoshell.ui.home.UiApp

/** 아이콘 없는 사이트의 글자 타일 배경 — 안정적인 해시 팔레트(회전 안 함). */
private val Palette = listOf(
    Color(0xFF39698F), Color(0xFF4A6572), Color(0xFF5C6BC0), Color(0xFF6A4FA3),
    Color(0xFF7B4B94), Color(0xFF8D5B6B), Color(0xFF96652F), Color(0xFF2E7D32),
    Color(0xFF00695C), Color(0xFF00838F), Color(0xFF0277BD), Color(0xFF455A64),
)

/** 사이트 테마색 우선, 없으면 id 기반 팔레트 색. */
fun tileColor(app: UiApp): Color {
    app.themeColor?.let { hex ->
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
            ?.let { return it }
    }
    val hash = (app.id + app.name).hashCode()
    return Palette[((hash % Palette.size) + Palette.size) % Palette.size]
}
