package app.aaps.wear.interaction.actions

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.wear.activity.ConfirmationActivity
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import app.aaps.wear.interaction.utils.WizardCalculationRow
import app.aaps.wear.interaction.utils.WizardResultViewBuilder
import dagger.android.support.DaggerAppCompatActivity
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.text.DecimalFormat
import javax.inject.Inject

/**
 * Enhanced Wizard Result Activity
 * Displays bolus wizard calculation results in a structured, visual table format
 */
class WizardResultActivity : DaggerAppCompatActivity() {

    @Inject lateinit var rxBus: RxBus
    private val disposable = CompositeDisposable()

    private lateinit var totalInsulinView: TextView
    private lateinit var carbsView: TextView
    private lateinit var settingsView: TextView
    private lateinit var calculationRowsContainer: LinearLayout
    private lateinit var calculationHeader: LinearLayout
    private lateinit var calculationDetails: LinearLayout
    private lateinit var expandIcon: TextView
    private lateinit var scrollView: androidx.core.widget.NestedScrollView

    private var isExpanded = false
    private var totalInsulin = 0.0
    private var carbs = 0

    private var timestamp: Long = 0
    private val decimalFormat = DecimalFormat("0.00")
    private val oneDecimalFormat = DecimalFormat("0.0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_wizard_result)

            // Vibrate when result appears
            vibrateOnResult()

            initViews()
            parseAndDisplayData()
        } catch (e: Exception) {
            android.util.Log.e("WizardResultActivity", "Error in onCreate", e)
            finish()
        }
    }

    private fun vibrateOnResult() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(100)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WizardResultActivity", "Vibration error", e)
        }
    }

    private fun initViews() {
        totalInsulinView = findViewById(R.id.wizard_total_insulin)
        carbsView = findViewById(R.id.wizard_carbs)
        settingsView = findViewById(R.id.wizard_settings)
        calculationRowsContainer = findViewById(R.id.wizard_calculation_rows)
        calculationHeader = findViewById(R.id.calculation_header)
        calculationDetails = findViewById(R.id.calculation_details)
        expandIcon = findViewById(R.id.expand_icon)
        scrollView = findViewById(R.id.scroll_view)

        // Setup collapse/expand functionality
        calculationHeader.setOnClickListener {
            toggleCalculationDetails()
        }

        // Enable swipe detection for going to confirmation screen
        // Wear OS will handle swipe-to-dismiss naturally
    }

    private fun parseAndDisplayData() {
        // Get data from intent
        timestamp = intent.getLongExtra("timestamp", 0L)
        totalInsulin = intent.getDoubleExtra("total_insulin", 0.0)
        carbs = intent.getIntExtra("carbs", 0)
        val ic = intent.getDoubleExtra("ic", 0.0)
        val sens = intent.getDoubleExtra("sens", 0.0)

        // Get individual insulin components
        val insulinCarbs = intent.getDoubleExtra("insulin_carbs", 0.0)
        val insulinBg = intent.getDoubleExtra("insulin_bg", Double.NaN)
        val insulinCob = intent.getDoubleExtra("insulin_cob", Double.NaN)
        val insulinBolusIob = intent.getDoubleExtra("insulin_bolus_iob", Double.NaN)
        val insulinBasalIob = intent.getDoubleExtra("insulin_basal_iob", Double.NaN)
        val insulinTrend = intent.getDoubleExtra("insulin_trend", Double.NaN)
        val insulinSuperbolus = intent.getDoubleExtra("insulin_superbolus", Double.NaN)

        val tempTarget = intent.getStringExtra("temp_target") ?: ""
        val percentage = intent.getIntExtra("percentage", 100)
        val totalBeforePercentage = intent.getDoubleExtra("total_before_percentage", Double.NaN)
        val cob = intent.getDoubleExtra("cob", 0.0)

        // Display summary card
        totalInsulinView.text = decimalFormat.format(totalInsulin)

        try {
            carbsView.text = getString(R.string.wizard_carbs_format, carbs)
        } catch (e: Exception) {
            carbsView.text = "${carbs}g carbs"
        }

        // Display settings (IC and ISF)
        if (ic > 0 && sens > 0) {
            try {
                settingsView.text = getString(R.string.wizard_settings_format,
                                              oneDecimalFormat.format(ic),
                                              oneDecimalFormat.format(sens))
            } catch (e: Exception) {
                settingsView.text = "IC: ${oneDecimalFormat.format(ic)} | ISF: ${oneDecimalFormat.format(sens)}"
            }
        }

        // Build calculation rows dynamically
        // Order: BG, 15' Trend, IOB, COB, Carbs
        try {
            val builder = WizardResultViewBuilder(this)
            val rows = mutableListOf<WizardCalculationRow>()

            // 1. BG correction if used - with TT on same line
            if (!insulinBg.isNaN()) {
                val bgLabel = if (tempTarget.isNotEmpty()) {
                    "BG (TT: $tempTarget)"
                } else {
                    "BG"
                }
                rows.add(WizardCalculationRow(bgLabel, insulinBg))
            }

            // 2. Trend if used
            if (!insulinTrend.isNaN() && insulinTrend != 0.0) {
                rows.add(WizardCalculationRow("15' Trend", insulinTrend))
            }

            // 3. IOB if used (combine bolus and basal)
            val totalIob = when {
                !insulinBolusIob.isNaN() && !insulinBasalIob.isNaN() ->
                    insulinBolusIob + insulinBasalIob
                !insulinBolusIob.isNaN() -> insulinBolusIob
                !insulinBasalIob.isNaN() -> insulinBasalIob
                else -> Double.NaN
            }
            if (!totalIob.isNaN() && totalIob != 0.0) {
                rows.add(WizardCalculationRow("IOB", totalIob))
            }

            // 4. COB if used
            if (!insulinCob.isNaN() && insulinCob != 0.0) {
                rows.add(WizardCalculationRow("COB", insulinCob))
            }

            // 5. Carbs - always last
            rows.add(WizardCalculationRow("Carbs", insulinCarbs))

            // Add superbolus if used
            if (!insulinSuperbolus.isNaN() && insulinSuperbolus != 0.0) {
                rows.add(WizardCalculationRow("Superbolus", insulinSuperbolus))
            }

            // Add all rows to UI
            rows.forEach { row ->
                builder.addCalculationRow(calculationRowsContainer, row)
            }

            // Always add Total at the bottom for comparison with top
            if (percentage != 100 && !totalBeforePercentage.isNaN()) {
                // Add percentage calculation breakdown
                val divider = View(this)
                divider.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    2
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
                divider.setBackgroundColor(getColor(R.color.divider))
                calculationRowsContainer.addView(divider)

                // Add subtotal row
                rows.add(WizardCalculationRow("Subtotal", totalBeforePercentage))
                builder.addCalculationRow(calculationRowsContainer, rows.last())

                // Add percentage adjustment row
                val percentageAdjustment = totalInsulin - totalBeforePercentage
                rows.add(WizardCalculationRow("${percentage}%", percentageAdjustment))
                builder.addCalculationRow(calculationRowsContainer, rows.last())
            }

            // Always show Total at the bottom (with or without percentage)
            val divider2 = View(this)
            divider2.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            divider2.setBackgroundColor(getColor(R.color.divider))
            calculationRowsContainer.addView(divider2)

            // Add final total row
            rows.add(WizardCalculationRow("Total", totalInsulin))
            builder.addCalculationRow(calculationRowsContainer, rows.last())

        } catch (e: Exception) {
            android.util.Log.e("WizardResultActivity", "Error building calculation rows", e)
        }

        // Percentage row removed - info is in the calculation breakdown
    }

    private fun toggleCalculationDetails() {
        isExpanded = !isExpanded

        if (isExpanded) {
            // Expand
            calculationDetails.visibility = View.VISIBLE
            expandIcon.text = "▲"

            // Scroll to bottom after a short delay
            scrollView.postDelayed({
                                       scrollView.fullScroll(View.FOCUS_DOWN)
                                   }, 100)
        } else {
            // Collapse
            calculationDetails.visibility = View.GONE
            expandIcon.text = "▼"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Swipe back = cancel
        super.onBackPressed()
    }

    // User needs to swipe LEFT to get to confirmation screen
    // This is handled by creating WizardConfirmActivity and setting it up in manifest
    // with the proper activity hierarchy

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
    }
}