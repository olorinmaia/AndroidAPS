package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R
import dagger.android.AndroidInjection

class SgvComplication : ModernBaseComplicationProviderService() {

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
                val shortText = bgData.sgvString + bgData.slopeArrow + "\uFE0E"

                val rawDelta = if (displayFormat.sp.getBoolean(R.string.key_show_detailed_delta, false))
                    bgData.deltaDetailed else bgData.delta

                val deltaSymbol = displayFormat.deltaSymbol()

                val titleComplicationText = displayFormat.buildDeltaWithLiveTimeDifference(bgData.timeStamp, rawDelta, deltaSymbol)

                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(text = shortText).build(),
                    contentDescription = PlainComplicationText.Builder(text = "Glucose $shortText").build()
                )
                    .setTitle(titleComplicationText)
                    .setTapAction(complicationPendingIntent)
                    .build()
            }
            else -> {
                aapsLogger.warn(LTag.WEAR, "SgvComplication unexpected type: $type")
                null
            }
        }
    }

    override fun getProviderCanonicalName(): String = SgvComplication::class.java.canonicalName!!
}
