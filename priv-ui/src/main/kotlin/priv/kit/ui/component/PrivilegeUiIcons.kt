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

private var _contentCopy: ImageVector? = null
private var _arrowBack: ImageVector? = null
private var _playArrow: ImageVector? = null
private var _stop: ImageVector? = null
