package app.aaps.auto.data

import app.aaps.auto.R
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.toStringShort
import javax.inject.Inject
import kotlin.math.abs

class AutoDataProvider @Inject constructor(
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val lastBgData: LastBgData,
    private val trendCalculator: TrendCalculator,
    private val rh: ResourceHelper,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val dateUtil: DateUtil,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val config: Config,
    private val decimalFormatter: DecimalFormatter,
    private val persistenceLayer: PersistenceLayer,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val processedDeviceStatusData: ProcessedDeviceStatusData
) {

    suspend fun loadData(): AutoData {
        val unavailable = rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

        val lastBg = lastBgData.lastBg()
        val bgText = lastBg?.let { profileUtil.fromMgdlToStringInUnits(it.recalculated) } ?: unavailable
        val bgValid = lastBgData.isActualBg()

        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val deltaText = glucoseStatus?.let { profileUtil.fromMgdlToSignedStringInUnits(it.delta) } ?: unavailable
        val timeAgoText = dateUtil.minOrSecAgo(rh, lastBg?.timestamp)

        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        val trendSymbol = if (lastBg != null) (trendArrow ?: TrendArrow.FLAT).toUnicode() else ""

        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val iobTotal = bolusIob.iob + basalIob.basaliob
        val iobText = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, iobTotal)

        // Same format as the persistent notification (TB.toStringShort)
        val activeTbr = processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())
        val basalDisplayText: String = activeTbr?.toStringShort(rh) ?: (profileFunction.getProfile()?.let {
            rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, it.getBasal())
        } ?: unavailable)

        val cobInfo = iobCobCalculator.getCobInfo("Auto COB")
        var cobText = cobInfo.displayText(rh, decimalFormatter) ?: unavailable
        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (constraintsProcessed != null && lastRun != null && constraintsProcessed.carbsReq > 0) {
            val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
            if (lastCarbsTime < lastRun.lastAPSRun) {
                cobText += " (+" + constraintsProcessed.carbsReq + "g)"
            }
        }

        val units = profileFunction.getUnits()
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        var tempTargetText = ""
        var tempTargetActive = false
        if (tempTarget != null) {
            val reasonLabel = when (tempTarget.reason) {
                TT.Reason.EATING_SOON  -> rh.gs(app.aaps.core.ui.R.string.eatingsoon)
                TT.Reason.ACTIVITY     -> rh.gs(app.aaps.core.ui.R.string.activity)
                TT.Reason.HYPOGLYCEMIA -> rh.gs(app.aaps.core.ui.R.string.hypo)
                TT.Reason.AUTOMATION   -> rh.gs(app.aaps.core.ui.R.string.automation)
                TT.Reason.WEAR         -> rh.gs(app.aaps.core.ui.R.string.wear)
                TT.Reason.CUSTOM       -> rh.gs(app.aaps.core.ui.R.string.custom)
            }
            tempTargetText = "$reasonLabel: " +
                profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units) +
                " " + dateUtil.untilString(tempTarget.end, rh)
            tempTargetActive = true
        } else {
            profileFunction.getProfile()?.let { profile ->
                val targetUsed = when {
                    config.APS         -> loop.lastRun?.constraintsProcessed?.targetBG ?: 0.0
                    config.AAPSCLIENT  -> processedDeviceStatusData.getAPSResult()?.targetBG ?: 0.0
                    else               -> 0.0
                }
                tempTargetText = if (targetUsed != 0.0 && abs(profile.getTargetMgdl() - targetUsed) > 0.01) {
                    tempTargetActive = true
                    rh.gs(R.string.auto_target_adjusted, profileUtil.toTargetRangeString(targetUsed, targetUsed, GlucoseUnit.MGDL, units))
                } else {
                    profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units)
                }
            }
        }

        val profileText = profileFunction.getProfileNameWithRemainingTime()

        val mode = loop.runningMode()
        val loopModeText = rh.gs(
            when (mode) {
                RM.Mode.OPEN_LOOP         -> app.aaps.core.ui.R.string.openloop
                RM.Mode.CLOSED_LOOP       -> app.aaps.core.ui.R.string.closedloop
                RM.Mode.CLOSED_LOOP_LGS   -> R.string.auto_loop_lgs
                RM.Mode.DISABLED_LOOP     -> R.string.auto_loop_disabled
                RM.Mode.SUPER_BOLUS       -> app.aaps.core.ui.R.string.superbolus
                RM.Mode.DISCONNECTED_PUMP -> app.aaps.core.ui.R.string.disconnected
                RM.Mode.SUSPENDED_BY_PUMP,
                RM.Mode.SUSPENDED_BY_USER,
                RM.Mode.SUSPENDED_BY_DST  -> R.string.auto_loop_suspended
                RM.Mode.RESUME            -> R.string.auto_loop_resume
            }
        )

        return AutoData(
            bgText = bgText,
            bgValid = bgValid,
            deltaText = deltaText,
            trendSymbol = trendSymbol,
            timeAgoText = timeAgoText,
            iobText = iobText,
            cobText = cobText,
            basalDisplayText = basalDisplayText,
            loopModeText = loopModeText,
            profileText = profileText,
            tempTargetText = tempTargetText,
            tempTargetActive = tempTargetActive
        )
    }

    private fun TrendArrow.toUnicode(): String = when (this) {
        TrendArrow.TRIPLE_UP       -> "⬆⬆"
        TrendArrow.DOUBLE_UP       -> "↑↑"
        TrendArrow.SINGLE_UP       -> "↑"
        TrendArrow.FORTY_FIVE_UP   -> "↗"
        TrendArrow.FLAT            -> "→"
        TrendArrow.FORTY_FIVE_DOWN -> "↘"
        TrendArrow.SINGLE_DOWN     -> "↓"
        TrendArrow.DOUBLE_DOWN     -> "↓↓"
        TrendArrow.TRIPLE_DOWN     -> "⬇⬇"
        TrendArrow.NONE            -> "?"
    }
}
