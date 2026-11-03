package org.servo.servoshell.ui.webapp

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 화면 좌측 엣지(24dp)의 가로 드래그를 실시간 추적하는 투명 존.
 * 전체 화면 제스처가 아니므로 페이지 내 가로 스크롤(캐러셀 등)을 방해하지 않는다.
 * 드래그 델타(px)를 그대로 올려보내고, 놓을 때 onRelease 로 마감한다.
 */
@Composable
fun EdgeSwipeZone(
    onDragDelta: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(24.dp)
            .fillMaxHeight()
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
}
