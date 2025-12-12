package app.aaps.wear.interaction.actions

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.wear.activity.ConfirmationActivity
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import dagger.android.support.DaggerAppCompatActivity
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.text.DecimalFormat
import javax.inject.Inject

/**
 * Wizard Confirmation Activity
 * Second screen after swiping - shows final confirmation with buttons
 */
class WizardConfirmActivity : DaggerAppCompatActivity() {

    @Inject lateinit var rxBus: RxBus
    private val disposable = CompositeDisposable()

    private lateinit var totalInsulinView: TextView
    private lateinit var carbsView: TextView
    private lateinit var confirmButton: ImageView

    private var timestamp: Long = 0
    private val decimalFormat = DecimalFormat("0.00")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard_confirm)

        initViews()
        loadData()
        setupClickListeners()
    }

    private fun initViews() {
        totalInsulinView = findViewById(R.id.confirm_total_insulin)
        carbsView = findViewById(R.id.confirm_carbs)
        confirmButton = findViewById(R.id.confirm_button)
    }

    private fun loadData() {
        timestamp = intent.getLongExtra("timestamp", 0L)
        val totalInsulin = intent.getDoubleExtra("total_insulin", 0.0)
        val carbs = intent.getIntExtra("carbs", 0)

        totalInsulinView.text = "${decimalFormat.format(totalInsulin)} U"
        carbsView.text = "${carbs}g carbs"
    }

    private fun setupClickListeners() {
        confirmButton.setOnClickListener {
            // Send confirmation to phone
            rxBus.send(
                EventWearToMobile(
                    EventData.ActionWizardConfirmed(timestamp)
                )
            )

            // Show success animation
            val intent = Intent(this, ConfirmationActivity::class.java).apply {
                putExtra(
                    ConfirmationActivity.EXTRA_ANIMATION_TYPE,
                    ConfirmationActivity.SUCCESS_ANIMATION
                )
                putExtra(
                    ConfirmationActivity.EXTRA_MESSAGE,
                    getString(R.string.action_wizard_confirmation)
                )
            }
            startActivity(intent)
            finishAffinity()
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
    }
}