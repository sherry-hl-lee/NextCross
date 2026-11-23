package com.fit5120tp10.nextcross.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** Shared chevron used by standard page-level back buttons. */
@Composable
internal fun BackNavigationIcon(color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        drawLine(
            color = color,
            start = Offset(size.width * .72f, size.height * .2f),
            end = Offset(size.width * .28f, size.height * .5f),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * .28f, size.height * .5f),
            end = Offset(size.width * .72f, size.height * .8f),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}
