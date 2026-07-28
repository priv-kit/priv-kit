@file:Suppress("ObjectPropertyName")

package priv.kit.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
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

    val GitHub: ImageVector
        get() {
            if (_gitHub != null) return _gitHub!!
            _gitHub = ImageVector.Builder(
                name = "github",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 32f,
                viewportHeight = 32f,
            ).apply {
                addPath(
                    pathData = PathParser().parsePathString(
                        """
                        M16 0
                        C7.16 0 0 7.16 0 16
                        C0 23.08 4.58 29.06 10.94 31.18
                        C11.74 31.32 12.04 30.84 12.04 30.42
                        C12.04 30.04 12.02 28.78 12.02 27.44
                        C8 28.18 6.96 26.46 6.64 25.56
                        C6.46 25.1 5.68 23.68 5 23.3
                        C4.44 23 3.64 22.26 4.98 22.24
                        C6.24 22.22 7.14 23.4 7.44 23.88
                        C8.88 26.3 11.18 25.62 12.1 25.2
                        C12.24 24.16 12.66 23.46 13.12 23.06
                        C9.56 22.66 5.84 21.28 5.84 15.16
                        C5.84 13.42 6.46 11.98 7.48 10.86
                        C7.32 10.46 6.76 8.82 7.64 6.62
                        C7.64 6.62 8.98 6.2 12.04 8.26
                        C13.32 7.9 14.68 7.72 16.04 7.72
                        C17.4 7.72 18.76 7.9 20.04 8.26
                        C23.1 6.18 24.44 6.62 24.44 6.62
                        C25.32 8.82 24.76 10.46 24.6 10.86
                        C25.62 11.98 26.24 13.4 26.24 15.16
                        C26.24 21.3 22.5 22.66 18.94 23.06
                        C19.52 23.56 20.02 24.52 20.02 26.02
                        C20.02 28.16 20 29.88 20 30.42
                        C20 30.84 20.3 31.34 21.1 31.18
                        C27.42 29.06 32 23.06 32 16
                        C32 7.16 24.84 0 16 0
                        V0
                        Z
                        """.trimIndent(),
                    ).toNodes(),
                    pathFillType = PathFillType.EvenOdd,
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    stroke = null,
                    strokeAlpha = 1f,
                    strokeLineWidth = 1f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Bevel,
                    strokeLineMiter = 1f,
                )
            }.build()
            return _gitHub!!
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
private var _gitHub: ImageVector? = null
private var _playArrow: ImageVector? = null
private var _stop: ImageVector? = null
