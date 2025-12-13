package app.aaps.wear.interaction.actions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.wear.activity.ConfirmationActivity
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import dagger.android.support.AndroidSupportInjection
import dagger.android.support.DaggerFragment
import java.text.DecimalFormat
import javax.inject.Inject

class WizardConfirmFragment : DaggerFragment() {

    @Inject lateinit var rxBus: RxBus

    private val decimalFormat = DecimalFormat("0.00")
    private var timestamp: Long = 0

    companion object {
        fun newInstance(timestamp: Long, totalInsulin: Double, carbs: Int): WizardConfirmFragment {
            return WizardConfirmFragment().apply {
                arguments = Bundle().apply {
                    putLong("timestamp", timestamp)
                    putDouble("total_insulin", totalInsulin)
                    putInt("carbs", carbs)
                }
            }
        }
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_wizard_confirm, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = arguments ?: return

        timestamp = args.getLong("timestamp")
        val totalInsulin = args.getDouble("total_insulin")
        val carbs = args.getInt("carbs")

        view.findViewById<TextView>(R.id.confirm_total_insulin).text = getString(R.string.wizard_insulin_format, decimalFormat.format(totalInsulin))
        view.findViewById<TextView>(R.id.confirm_carbs).text = getString(R.string.wizard_carbs_format, carbs)

        view.findViewById<ImageView>(R.id.confirm_button).setOnClickListener {
            rxBus.send(EventWearToMobile(EventData.ActionWizardConfirmed(timestamp)))

            val intent = Intent(requireContext(), ConfirmationActivity::class.java).apply {
                putExtra(ConfirmationActivity.EXTRA_ANIMATION_TYPE, ConfirmationActivity.SUCCESS_ANIMATION)
                putExtra(ConfirmationActivity.EXTRA_MESSAGE, getString(R.string.wizard_success))
            }
            startActivity(intent)
            requireActivity().finishAffinity()
        }
    }
}