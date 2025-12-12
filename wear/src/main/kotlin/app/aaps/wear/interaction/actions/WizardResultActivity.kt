package app.aaps.wear.interaction.actions

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import app.aaps.wear.R
import dagger.android.support.DaggerAppCompatActivity

class WizardResultActivity : DaggerAppCompatActivity() {

    private lateinit var viewPager: ViewPager2

    // Data from intent
    private var timestamp: Long = 0
    private var totalInsulin: Double = 0.0
    private var carbs: Int = 0
    private var ic: Double = 0.0
    private var sens: Double = 0.0
    private var insulinCarbs: Double = 0.0
    private var insulinBg: Double? = null
    private var insulinCob: Double? = null
    private var insulinBolusIob: Double? = null
    private var insulinBasalIob: Double? = null
    private var insulinTrend: Double? = null
    private var insulinSuperbolus: Double? = null
    private var tempTarget: String? = null
    private var percentage: Int = 100
    private var totalBeforePercentage: Double? = null
    private var cob: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard_pager)

        // Get all data from intent
        timestamp = intent.getLongExtra("timestamp", 0L)
        totalInsulin = intent.getDoubleExtra("total_insulin", 0.0)
        carbs = intent.getIntExtra("carbs", 0)
        ic = intent.getDoubleExtra("ic", 0.0)
        sens = intent.getDoubleExtra("sens", 0.0)
        insulinCarbs = intent.getDoubleExtra("insulin_carbs", 0.0)

        val bgValue = intent.getDoubleExtra("insulin_bg", Double.NaN)
        insulinBg = if (!bgValue.isNaN()) bgValue else null

        val cobValue = intent.getDoubleExtra("insulin_cob", Double.NaN)
        insulinCob = if (!cobValue.isNaN()) cobValue else null

        val bolusIobValue = intent.getDoubleExtra("insulin_bolus_iob", Double.NaN)
        insulinBolusIob = if (!bolusIobValue.isNaN()) bolusIobValue else null

        val basalIobValue = intent.getDoubleExtra("insulin_basal_iob", Double.NaN)
        insulinBasalIob = if (!basalIobValue.isNaN()) basalIobValue else null

        val trendValue = intent.getDoubleExtra("insulin_trend", Double.NaN)
        insulinTrend = if (!trendValue.isNaN()) trendValue else null

        tempTarget = intent.getStringExtra("temp_target")
        percentage = intent.getIntExtra("percentage", 100)

        val beforePctValue = intent.getDoubleExtra("total_before_percentage", Double.NaN)
        totalBeforePercentage = if (!beforePctValue.isNaN()) beforePctValue else null

        cob = intent.getDoubleExtra("cob", 0.0)

        // Setup ViewPager
        viewPager = findViewById(R.id.wizard_view_pager)
        viewPager.adapter = WizardPagerAdapter(this)
        viewPager.offscreenPageLimit = 1

        // Start on first page (ResultFragment)
        viewPager.currentItem = 0
    }

    private inner class WizardPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> WizardResultFragment.newInstance(
                    totalInsulin, carbs, ic, sens, insulinCarbs,
                    insulinBg, insulinCob, insulinBolusIob, insulinBasalIob,
                    insulinTrend, tempTarget, percentage, totalBeforePercentage, cob
                )
                1 -> WizardConfirmFragment.newInstance(timestamp, totalInsulin, carbs)
                else -> throw IllegalStateException("Invalid position: $position")
            }
        }
    }
}