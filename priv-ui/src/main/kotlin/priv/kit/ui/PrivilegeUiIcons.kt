package priv.kit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object PrivilegeUiIcons {
    val Check: ImageVector
        get() {
            if (_check != null) return _check!!
            _check = ImageVector.Builder(
                name = "check",
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
                    moveTo(9.55f, 18f)
                    lineTo(3.85f, 12.3f)
                    lineTo(5.28f, 10.88f)
                    lineToRelative(4.28f, 4.28f)
                    lineTo(18.73f, 5.97f)
                    lineTo(20.15f, 7.4f)
                    lineTo(9.55f, 18f)
                    close()
                }
            }.build()
            return _check!!
        }

    val Notifications: ImageVector
        get() {
            if (_notifications != null) return _notifications!!
            _notifications = ImageVector.Builder(
                name = "notifications",
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
                    moveTo(4f, 19f)
                    verticalLineTo(17f)
                    horizontalLineTo(6f)
                    verticalLineTo(10f)
                    quadTo(6f, 7.93f, 7.25f, 6.31f)
                    reflectiveQuadTo(10.5f, 4.2f)
                    verticalLineTo(3.5f)
                    quadToRelative(0f, -0.63f, 0.44f, -1.06f)
                    reflectiveQuadTo(12f, 2f)
                    reflectiveQuadToRelative(1.06f, 0.44f)
                    reflectiveQuadTo(13.5f, 3.5f)
                    verticalLineTo(4.2f)
                    quadToRelative(2f, 0.5f, 3.25f, 2.11f)
                    reflectiveQuadTo(18f, 10f)
                    verticalLineToRelative(7f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    horizontalLineTo(4f)
                    close()
                    moveToRelative(8f, -7.5f)
                    close()
                    moveTo(12f, 22f)
                    quadToRelative(-0.82f, 0f, -1.41f, -0.59f)
                    reflectiveQuadTo(10f, 20f)
                    horizontalLineToRelative(4f)
                    quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                    reflectiveQuadTo(12f, 22f)
                    close()
                    moveTo(8f, 17f)
                    horizontalLineToRelative(8f)
                    verticalLineTo(10f)
                    quadTo(16f, 8.35f, 14.83f, 7.18f)
                    reflectiveQuadTo(12f, 6f)
                    reflectiveQuadTo(9.18f, 7.18f)
                    reflectiveQuadTo(8f, 10f)
                    verticalLineToRelative(7f)
                    close()
                }
            }.build()
            return _notifications!!
        }

    val NotificationsActive: ImageVector
        get() {
            if (_notificationsActive != null) return _notificationsActive!!
            _notificationsActive = ImageVector.Builder(
                name = "notifications_active",
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
                    moveTo(2f, 10f)
                    quadTo(2f, 7.5f, 3.11f, 5.41f)
                    reflectiveQuadTo(6.1f, 1.95f)
                    lineToRelative(1.17f, 1.6f)
                    quadTo(5.78f, 4.65f, 4.89f, 6.32f)
                    reflectiveQuadTo(4f, 10f)
                    horizontalLineTo(2f)
                    close()
                    moveToRelative(18f, 0f)
                    quadTo(20f, 8f, 19.11f, 6.32f)
                    reflectiveQuadTo(16.73f, 3.55f)
                    lineTo(17.9f, 1.95f)
                    quadToRelative(1.88f, 1.38f, 2.99f, 3.46f)
                    reflectiveQuadTo(22f, 10f)
                    horizontalLineTo(20f)
                    close()
                    moveTo(4f, 19f)
                    verticalLineTo(17f)
                    horizontalLineTo(6f)
                    verticalLineTo(10f)
                    quadTo(6f, 7.93f, 7.25f, 6.31f)
                    reflectiveQuadTo(10.5f, 4.2f)
                    verticalLineTo(3.5f)
                    quadToRelative(0f, -0.63f, 0.44f, -1.06f)
                    reflectiveQuadTo(12f, 2f)
                    reflectiveQuadToRelative(1.06f, 0.44f)
                    reflectiveQuadTo(13.5f, 3.5f)
                    verticalLineTo(4.2f)
                    quadToRelative(2f, 0.5f, 3.25f, 2.11f)
                    reflectiveQuadTo(18f, 10f)
                    verticalLineToRelative(7f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    horizontalLineTo(4f)
                    close()
                    moveToRelative(8f, -7.5f)
                    close()
                    moveTo(12f, 22f)
                    quadToRelative(-0.82f, 0f, -1.41f, -0.59f)
                    reflectiveQuadTo(10f, 20f)
                    horizontalLineToRelative(4f)
                    quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                    reflectiveQuadTo(12f, 22f)
                    close()
                    moveTo(8f, 17f)
                    horizontalLineToRelative(8f)
                    verticalLineTo(10f)
                    quadTo(16f, 8.35f, 14.83f, 7.18f)
                    reflectiveQuadTo(12f, 6f)
                    reflectiveQuadTo(9.18f, 7.18f)
                    reflectiveQuadTo(8f, 10f)
                    verticalLineToRelative(7f)
                    close()
                }
            }.build()
            return _notificationsActive!!
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

    val Help: ImageVector
        get() {
            if (_help != null) return _help!!
            _help = ImageVector.Builder(
                name = "help",
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
                    moveTo(12.84f, 17.64f)
                    quadTo(13.2f, 17.27f, 13.2f, 16.75f)
                    reflectiveQuadTo(12.84f, 15.86f)
                    reflectiveQuadTo(11.95f, 15.5f)
                    reflectiveQuadToRelative(-0.89f, 0.36f)
                    quadTo(10.7f, 16.23f, 10.7f, 16.75f)
                    reflectiveQuadToRelative(0.36f, 0.89f)
                    reflectiveQuadTo(11.95f, 18f)
                    reflectiveQuadToRelative(0.89f, -0.36f)
                    close()
                    moveTo(11.05f, 14.15f)
                    horizontalLineTo(12.9f)
                    quadToRelative(0f, -0.82f, 0.19f, -1.3f)
                    reflectiveQuadToRelative(1.06f, -1.3f)
                    quadTo(14.8f, 10.9f, 15.18f, 10.31f)
                    reflectiveQuadTo(15.55f, 8.9f)
                    quadToRelative(0f, -1.4f, -1.03f, -2.15f)
                    reflectiveQuadTo(12.1f, 6f)
                    quadTo(10.68f, 6f, 9.79f, 6.75f)
                    reflectiveQuadTo(8.55f, 8.55f)
                    lineTo(10.2f, 9.2f)
                    quadTo(10.33f, 8.75f, 10.76f, 8.23f)
                    reflectiveQuadTo(12.1f, 7.7f)
                    quadToRelative(0.8f, 0f, 1.2f, 0.44f)
                    reflectiveQuadTo(13.7f, 9.1f)
                    quadToRelative(0f, 0.5f, -0.3f, 0.94f)
                    reflectiveQuadToRelative(-0.75f, 0.81f)
                    quadToRelative(-1.1f, 0.97f, -1.35f, 1.47f)
                    reflectiveQuadToRelative(-0.25f, 1.82f)
                    close()
                    moveTo(12f, 22f)
                    quadTo(9.93f, 22f, 8.1f, 21.21f)
                    quadTo(6.28f, 20.43f, 4.93f, 19.08f)
                    quadTo(3.58f, 17.73f, 2.79f, 15.9f)
                    reflectiveQuadTo(2f, 12f)
                    quadTo(2f, 9.92f, 2.79f, 8.1f)
                    quadTo(3.58f, 6.27f, 4.93f, 4.93f)
                    quadTo(6.28f, 3.57f, 8.1f, 2.79f)
                    quadTo(9.93f, 2f, 12f, 2f)
                    reflectiveQuadToRelative(3.9f, 0.79f)
                    reflectiveQuadToRelative(3.17f, 2.14f)
                    quadToRelative(1.35f, 1.35f, 2.14f, 3.17f)
                    quadTo(22f, 9.92f, 22f, 12f)
                    reflectiveQuadToRelative(-0.79f, 3.9f)
                    reflectiveQuadToRelative(-2.14f, 3.17f)
                    quadToRelative(-1.35f, 1.35f, -3.17f, 2.14f)
                    reflectiveQuadTo(12f, 22f)
                    close()
                    moveToRelative(0f, -2f)
                    quadToRelative(3.35f, 0f, 5.68f, -2.32f)
                    reflectiveQuadTo(20f, 12f)
                    reflectiveQuadTo(17.68f, 6.32f)
                    reflectiveQuadTo(12f, 4f)
                    reflectiveQuadTo(6.33f, 6.32f)
                    reflectiveQuadTo(4f, 12f)
                    reflectiveQuadToRelative(2.33f, 5.68f)
                    reflectiveQuadTo(12f, 20f)
                    close()
                    moveToRelative(0f, -8f)
                    close()
                }
            }.build()
            return _help!!
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

private var _check: ImageVector? = null
private var _notifications: ImageVector? = null
private var _notificationsActive: ImageVector? = null
private var _contentCopy: ImageVector? = null
private var _arrowBack: ImageVector? = null
private var _help: ImageVector? = null
private var _playArrow: ImageVector? = null
private var _stop: ImageVector? = null
