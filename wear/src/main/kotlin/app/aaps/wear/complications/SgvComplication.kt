package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import app.aaps.wear.R
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.logging.LTag
import dagger.android.AndroidInjection

/**
 * Builds complication data for SGV.
 * Supports SHORT_TEXT type with glucose value, arrow, delta, and age.
 */
class SgvComplication : ModernBaseComplicationProviderService() {

    private val textPresentationSelector = "\uFE0E" // Text presentation selector

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
    }

    override fun buildComplicationData(
        type: ComplicationType,
        data: app.aaps.wear.data.ComplicationData,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        val bgData = data.bgData
        aapsLogger.debug(LTag.WEAR, "SgvComplication building: dataset=0 sgv=${bgData.sgvString} arrow=${bgData.slopeArrow}")

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                val shortText = "${bgData.sgvString}${bgData.slopeArrow}$textPresentationSelector"

                val rawDelta = if (displayFormat.sp.getBoolean(R.string.key_show_detailed_delta, false))
                    bgData.deltaDetailed else bgData.delta

                val shortTitleText = displayFormat.shortTimeWithDelta(bgData.timeStamp, rawDelta)

                val shortTitle = PlainComplicationText.Builder(text = shortTitleText).build()

                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text = shortText).build(),
                    contentDescription = PlainComplicationText.Builder(text = "Glucose $shortText").build()
                )
                    .setTitle(shortTitle)
                    .setTapAction(complicationPendingIntent)
                    .build()
            }

            else -> {
                aapsLogger.warn(LTag.WEAR, "${javaClass.simpleName} unexpected complication type: $type")
                null
            }
        }
    }

    override fun getProviderCanonicalName(): String = SgvComplication::class.java.canonicalName!!
}