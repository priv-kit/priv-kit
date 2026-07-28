@file:Suppress("ObjectPropertyName")

package priv.kit.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object PrivilegeUiIcons {
    val Warning: ImageVector
        get() {
            if (_warning != null) return _warning!!
            _warning = ImageVector.Builder(
                name = "warning",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    stroke = null,
                    strokeAlpha = 1f,
                    strokeLineWidth = 1f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Bevel,
                    strokeLineMiter = 1f,
                    pathFillType = PathFillType.NonZero,
                ) {
                    moveTo(1f, 21f)
                    lineTo(12f, 2f)
                    lineTo(23f, 21f)
                    horizontalLineTo(1f)
                    close()
                    moveTo(4.45f, 19f)
                    horizontalLineToRelative(15.1f)
                    lineTo(12f, 6f)
                    lineTo(4.45f, 19f)
                    close()
                    moveToRelative(8.26f, -1.29f)
                    quadTo(13f, 17.43f, 13f, 17f)
                    reflectiveQuadTo(12.71f, 16.29f)
                    reflectiveQuadTo(12f, 16f)
                    reflectiveQuadToRelative(-0.71f, 0.29f)
                    reflectiveQuadTo(11f, 17f)
                    reflectiveQuadToRelative(0.29f, 0.71f)
                    reflectiveQuadTo(12f, 18f)
                    reflectiveQuadToRelative(0.71f, -0.29f)
                    close()
                    moveTo(11f, 15f)
                    horizontalLineToRelative(2f)
                    verticalLineTo(10f)
                    horizontalLineTo(11f)
                    verticalLineToRelative(5f)
                    close()
                    moveToRelative(1f, -2.5f)
                    close()
                }
            }.build()
            return _warning!!
        }

    val ContentCopy: ImageVector
        get() {
            if (_contentCopy != null) return _contentCopy!!
            _contentCopy = ImageVector.Builder(
                name = "content_copy",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    stroke = null,
                    strokeAlpha = 1f,
                    strokeLineWidth = 1f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Bevel,
                    strokeLineMiter = 1f,
                    pathFillType = PathFillType.NonZero,
                ) {
                    moveTo(9f, 18f)
                    quadTo(8.18f, 18f, 7.59f, 17.41f)
                    reflectiveQuadTo(7f, 16f)
                    verticalLineTo(4f)
                    quadTo(7f, 3.17f, 7.59f, 2.59f)
                    reflectiveQuadTo(9f, 2f)
                    horizontalLineToRelative(9f)
                    quadToRelative(0.82f, 0f, 1.41f, 0.59f)
                    reflectiveQuadTo(20f, 4f)
                    verticalLineTo(16f)
                    quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                    reflectiveQuadTo(18f, 18f)
                    horizontalLineTo(9f)
                    close()
                    moveTo(9f, 16f)
                    horizontalLineToRelative(9f)
                    verticalLineTo(4f)
                    horizontalLineTo(9f)
                    verticalLineTo(16f)
                    close()
                    moveTo(5f, 22f)
                    quadTo(4.18f, 22f, 3.59f, 21.41f)
                    reflectiveQuadTo(3f, 20f)
                    verticalLineTo(6f)
                    horizontalLineTo(5f)
                    verticalLineTo(20f)
                    horizontalLineTo(16f)
                    verticalLineToRelative(2f)
                    horizontalLineTo(5f)
                    close()
                    moveTo(9f, 16f)
                    verticalLineTo(4f)
                    verticalLineTo(16f)
                    close()
                }
            }.build()
            return _contentCopy!!
        }

    val Close: ImageVector
        get() {
            if (_close != null) return _close!!
            _close = ImageVector.Builder(
                name = "close",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    stroke = null,
                    strokeAlpha = 1f,
                    strokeLineWidth = 1f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Bevel,
                    strokeLineMiter = 1f,
                    pathFillType = PathFillType.NonZero,
                ) {
                    moveTo(6.4f, 19f)
                    lineTo(5f, 17.6f)
                    lineTo(10.6f, 12f)
                    lineTo(5f, 6.4f)
                    lineTo(6.4f, 5f)
                    lineTo(12f, 10.6f)
                    lineTo(17.6f, 5f)
                    lineTo(19f, 6.4f)
                    lineTo(13.4f, 12f)
                    lineTo(19f, 17.6f)
                    lineTo(17.6f, 19f)
                    lineTo(12f, 13.4f)
                    lineTo(6.4f, 19f)
                    close()
                }
            }.build()
            return _close!!
        }

    val ArrowBack: ImageVector
        get() {
            if (_arrowBack != null) return _arrowBack!!
            _arrowBack = ImageVector.Builder(
                name = "arrow_back",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    stroke = null,
                    strokeAlpha = 1f,
                    strokeLineWidth = 1f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Bevel,
                    strokeLineMiter = 1f,
                    pathFillType = PathFillType.NonZero,
                ) {
                    moveTo(7.83f, 13f)
                    lineToRelative(5.6f, 5.6f)
                    lineTo(12f, 20f)
                    lineTo(4f, 12f)
                    lineTo(12f, 4f)
                    lineToRelative(1.43f, 1.4f)
                    lineTo(7.83f, 11f)
                    horizontalLineTo(20f)
                    verticalLineToRelative(2f)
                    horizontalLineTo(7.83f)
                    close()
                }
            }.build()
            return _arrowBack!!
        }

    val PlayArrow: ImageVector
        get() {
            if (_playArrow != null) return _playArrow!!
            _playArrow = ImageVector.Builder(
                name = "play_arrow",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    stroke = null,
                    strokeAlpha = 1f,
                    strokeLineWidth = 1f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Bevel,
                    strokeLineMiter = 1f,
                    pathFillType = PathFillType.NonZero,
                ) {
                    moveTo(8f, 19f)
                    verticalLineTo(5f)
                    lineToRelative(11f, 7f)
                    lineTo(8f, 19f)
                    close()
                    moveToRelative(2f, -7f)
                    close()
                    moveToRelative(0f, 3.35f)
                    lineTo(15.25f, 12f)
                    lineTo(10f, 8.65f)
                    verticalLineToRelative(6.7f)
                    close()
                }
            }.build()
            return _playArrow!!
        }

    val Stop: ImageVector
        get() {
            if (_stop != null) return _stop!!
            _stop = ImageVector.Builder(
                name = "stop",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    stroke = null,
                    strokeAlpha = 1f,
                    strokeLineWidth = 1f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Bevel,
                    strokeLineMiter = 1f,
                    pathFillType = PathFillType.NonZero,
                ) {
                    moveTo(8f, 8f)
                    verticalLineToRelative(8f)
                    verticalLineTo(8f)
                    close()
                    moveTo(6f, 18f)
                    verticalLineTo(6f)
                    horizontalLineTo(18f)
                    verticalLineTo(18f)
                    horizontalLineTo(6f)
                    close()
                    moveTo(8f, 16f)
                    horizontalLineToRelative(8f)
                    verticalLineTo(8f)
                    horizontalLineTo(8f)
                    verticalLineToRelative(8f)
                    close()
                }
            }.build()
            return _stop!!
        }
}

private var _warning: ImageVector? = null
private var _contentCopy: ImageVector? = null
private var _close: ImageVector? = null
private var _arrowBack: ImageVector? = null
private var _playArrow: ImageVector? = null
private var _stop: ImageVector? = null
