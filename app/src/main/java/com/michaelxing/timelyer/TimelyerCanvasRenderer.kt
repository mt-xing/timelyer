package com.michaelxing.timelyer

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.BatteryManager
import android.util.Log
import android.view.SurfaceHolder
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSetting
import com.michaelxing.timelyer.data.watchface.ColorStyleIdAndResourceIds
import com.michaelxing.timelyer.data.watchface.WatchFaceColorPalette.Companion.convertToWatchFaceColorPalette
import com.michaelxing.timelyer.data.watchface.WatchFaceData
import com.michaelxing.timelyer.utils.COLOR_STYLE_SETTING
import com.michaelxing.timelyer.utils.DRAW_HOUR_PIPS_STYLE_SETTING
import com.michaelxing.timelyer.utils.WATCH_HAND_LENGTH_STYLE_SETTING
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


const val dateVertSpace = 26f
val f = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US)

// Default for how long each frame is displayed at expected frame rate.
private const val FRAME_PERIOD_MS_DEFAULT: Long = 100L

/**
 * Renders watch face via data in Room database. Also, updates watch face state based on setting
 * changes by user via [userStyleRepository.addUserStyleListener()].
 */
class TimelyerCanvasRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    private val complicationSlotsManager: ComplicationSlotsManager,
    currentUserStyleRepository: CurrentUserStyleRepository,
    canvasType: Int
) : Renderer.CanvasRenderer2<TimelyerCanvasRenderer.AnalogSharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    canvasType,
    FRAME_PERIOD_MS_DEFAULT,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false
) {
    class AnalogSharedAssets : SharedAssets {
        override fun onDestroy() {
        }
    }

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Represents all data needed to render the watch face. All value defaults are constants. Only
    // three values are changeable by the user (color scheme, ticks being rendered, and length of
    // the minute arm). Those dynamic values are saved in the watch face APIs and we update those
    // here (in the renderer) through a Kotlin Flow.
    private var watchFaceData: WatchFaceData = WatchFaceData()

    // Converts resource ids into Colors and ComplicationDrawable.
    private var watchFaceColors = convertToWatchFaceColorPalette(
        context,
        watchFaceData.activeColorStyle,
        watchFaceData.ambientColorStyle
    )

    // Changed when setting changes cause a change in the minute hand arm (triggered by user in
    // updateUserStyle() via userStyleRepository.addUserStyleListener()).
    private var armLengthChangedRecalculateClockHands: Boolean = false

    init {
        scope.launch {
            currentUserStyleRepository.userStyle.collect { userStyle ->
                updateWatchFaceData(userStyle)
            }
        }
    }

    override suspend fun createSharedAssets(): AnalogSharedAssets {
        return AnalogSharedAssets()
    }

    /*
     * Triggered when the user makes changes to the watch face through the settings activity. The
     * function is called by a flow.
     */
    private fun updateWatchFaceData(userStyle: UserStyle) {
        Log.d(TAG, "updateWatchFace(): $userStyle")

        var newWatchFaceData: WatchFaceData = watchFaceData

        // Loops through user style and applies new values to watchFaceData.
        for (options in userStyle) {
            when (options.key.id.toString()) {
                COLOR_STYLE_SETTING -> {
                    val listOption = options.value as
                        UserStyleSetting.ListUserStyleSetting.ListOption

                    newWatchFaceData = newWatchFaceData.copy(
                        activeColorStyle = ColorStyleIdAndResourceIds.getColorStyleConfig(
                            listOption.id.toString()
                        )
                    )
                }
                DRAW_HOUR_PIPS_STYLE_SETTING -> {
                    val booleanValue = options.value as
                        UserStyleSetting.BooleanUserStyleSetting.BooleanOption

                    newWatchFaceData = newWatchFaceData.copy(
                        drawHourPips = booleanValue.value
                    )
                }
                WATCH_HAND_LENGTH_STYLE_SETTING -> {
                    val doubleValue = options.value as
                        UserStyleSetting.DoubleRangeUserStyleSetting.DoubleRangeOption

                    // The arm lengths are usually only calculated the first time the watch face is
                    // loaded to reduce the ops in the onDraw(). Because we updated the minute hand
                    // watch length, we need to trigger a recalculation.
                    armLengthChangedRecalculateClockHands = true

                    // Updates length of minute hand based on edits from user.
                    val newMinuteHandDimensions = newWatchFaceData.minuteHandDimensions.copy(
                        lengthFraction = doubleValue.value.toFloat()
                    )

                    newWatchFaceData = newWatchFaceData.copy(
                        minuteHandDimensions = newMinuteHandDimensions
                    )
                }
            }
        }

        // Only updates if something changed.
        if (watchFaceData != newWatchFaceData) {
            watchFaceData = newWatchFaceData

            // Recreates Color and ComplicationDrawable from resource ids.
            watchFaceColors = convertToWatchFaceColorPalette(
                context,
                watchFaceData.activeColorStyle,
                watchFaceData.ambientColorStyle
            )

            // Applies the user chosen complication color scheme changes. ComplicationDrawables for
            // each of the styles are defined in XML so we need to replace the complication's
            // drawables.
            for ((_, complication) in complicationSlotsManager.complicationSlots) {
                ComplicationDrawable.getDrawable(
                    context,
                    watchFaceColors.complicationStyleDrawableId
                )?.let {
                    (complication.renderer as CanvasComplicationDrawable).drawable = it
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        scope.cancel("AnalogWatchCanvasRenderer scope clear() request")
        super.onDestroy()
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: AnalogSharedAssets
    ) {
        canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)

        for ((_, complication) in complicationSlotsManager.complicationSlots) {
            if (complication.enabled) {
                complication.renderHighlightLayer(canvas, zonedDateTime, renderParameters)
            }
        }
    }

    private val textBounds = Rect()
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        typeface = Typeface.DEFAULT
    }
    private val rectPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float) {
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        canvas.drawText(
            text,
            x - (textBounds.width() / 2f),
            y + (textBounds.height() / 2f),
            textPaint
        )
    }

    private fun drawBattery(canvas: Canvas, bounds: Rect) {
        textPaint.textSize = 20f
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val batteryPct: Int = batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            level * 100 / scale
        } ?: 0

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
            || status == BatteryManager.BATTERY_STATUS_FULL

        val batteryString = "$batteryPct%"

        drawText(canvas, batteryString, bounds.exactCenterX() - (if (isCharging) 50 else 35), 40f)
        rectPaint.color = if (batteryPct < 20) Color.RED else Color.WHITE
        canvas.drawRect(bounds.exactCenterX(), 30f, bounds.exactCenterX() + 60f, 52f, rectPaint)
        canvas.drawRect(bounds.exactCenterX() + 60f, 35f, bounds.exactCenterX() + 64f, 46f, rectPaint)
        if (batteryPct < 100) {
            val offset = 2f;
            val totalWidth = 60 - (offset * 2)
            val widthOffset = totalWidth * (batteryPct / 100f)
            rectPaint.color = Color.BLACK
            canvas.drawRect(bounds.exactCenterX() + offset + widthOffset, 30f + offset, bounds.exactCenterX() + 60f - offset, 52f - offset, rectPaint)
        }

        if (isCharging) {
            drawText(canvas, if (status == BatteryManager.BATTERY_STATUS_CHARGING) "⚡" else "\uD83D\uDD0C", bounds.exactCenterX() - 15, 35f)
        }
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: AnalogSharedAssets
    ) {
        canvas.drawColor(Color.BLACK)

        val timeString = padToTwoDigits(zonedDateTime.hour) + ":" + padToTwoDigits(zonedDateTime.minute)

        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 90f

        if (renderParameters.drawMode == DrawMode.AMBIENT) {
            drawText(canvas, timeString, bounds.exactCenterX(), bounds.exactCenterY() - 20)
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = 30f
            drawText(canvas, zonedDateTime.format(f), bounds.exactCenterX(), bounds.exactCenterY() + 45)
            drawText(canvas, zonedDateTime.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, Locale.US), bounds.exactCenterX(), bounds.exactCenterY() + 80)
            return
        }

        textPaint.getTextBounds(timeString, 0, timeString.length, textBounds)
        canvas.drawText(
            timeString,
            bounds.width() - textBounds.width() - 50f,
            bounds.exactCenterY() + (textBounds.height() / 2f) - 40,
            textPaint
        )


        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = 36f

        drawText(canvas, zonedDateTime.format(f), bounds.exactCenterX(), bounds.exactCenterY() - 110)
        drawBattery(canvas, bounds)

        textPaint.textSize = dateVertSpace - 6
        drawText(canvas, padToTwoDigits(zonedDateTime.second), bounds.width() - 50f, bounds.exactCenterY() + 10)

        drawText(canvas, "UTC" + zonedDateTime.offset.id.replace("Z".toRegex(), "+00:00"), bounds.exactCenterX(), bounds.exactCenterY() + 10)

        val mondayOfWeek = zonedDateTime.toLocalDate().minusDays(zonedDateTime.dayOfWeek.value - 1L)
        for (i in 0 until 7) {
            val isDayOfWeek = (i + 1) == zonedDateTime.dayOfWeek.value
            val x = (bounds.width() / 9f) * (i + 1.5f)
            val y = bounds.exactCenterY() + 50

            if (isDayOfWeek) {
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.color = Color.CYAN
            }

            drawText(
                canvas,
                DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.US),
                x, y
            )

            if (isDayOfWeek) {
                textPaint.color = Color.WHITE
                textPaint.typeface = Typeface.DEFAULT
            }
            textPaint.typeface = Typeface.DEFAULT

            for (j in 0 until 3) {
                val isToday = isDayOfWeek && j == 1
                if (isToday) {
                    rectPaint.color = Color.CYAN
                    canvas.drawRect(x - (bounds.width() / 18f), y + (dateVertSpace * 1.5f), x + (bounds.width() / 18f), y + (dateVertSpace * 2.5f), rectPaint)
                    textPaint.color = Color.BLACK
                }
                drawText(
                    canvas,
                    mondayOfWeek.plusDays(i + (7L * (j - 1))).dayOfMonth.toString(),
                    x, y + ((j + 1) * dateVertSpace)
                )
                if (isToday) {
                    textPaint.color = Color.WHITE
                }
            }
        }

        rectPaint.color = Color.WHITE
        for (i in 0 until 4) {
            val y = bounds.exactCenterY() + 63 + (i * dateVertSpace)
            canvas.drawRect(0f, y, bounds.width().toFloat(), y + 1, rectPaint)
        }

        drawComplications(canvas, zonedDateTime)
    }

    // ----- All drawing functions -----
    private fun drawComplications(canvas: Canvas, zonedDateTime: ZonedDateTime) {
        for ((_, complication) in complicationSlotsManager.complicationSlots) {
            if (complication.enabled) {
                complication.render(canvas, zonedDateTime, renderParameters)
            }
        }
    }

    companion object {
        private const val TAG = "TimelyerCanvasRenderer"

        fun padToTwoDigits(v: Int): String {
            if (v < 10) {
                return if (v == 0) "00" else "0$v"
            }
            return v.toString()
        }
    }
}
