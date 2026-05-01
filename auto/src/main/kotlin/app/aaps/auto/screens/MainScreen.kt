package app.aaps.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.aaps.auto.data.AutoData
import app.aaps.auto.data.AutoDataProvider
import app.aaps.core.interfaces.configuration.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainScreen(
    carContext: CarContext,
    private val dataProvider: AutoDataProvider,
    private val config: Config
) : Screen(carContext) {

    // Placeholder shown before first load — must have IDENTICAL row structure to buildRows(data)
    // so the loading→data transition counts as a refresh (no quota increment).
    @Volatile private var autoData: AutoData = AutoData(
        bgText = "--", bgValid = false,
        deltaText = "--", trendSymbol = "", timeAgoText = "--",
        iobText = "--", cobText = "--", basalDisplayText = "--",
        loopModeText = "…", profileText = "--",
        tempTargetText = "--", tempTargetActive = false
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = scope.cancel()
        })
        scope.launch {
            val ready = config.appInitialized || withTimeoutOrNull(AWAIT_INIT_TIMEOUT_MS) {
                config.initProgressFlow.first { it.done }
            } != null
            if (!ready) return@launch
            while (true) {
                autoData = dataProvider.loadData()
                invalidate()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onGetTemplate(): Template = buildTemplate(autoData)

    private fun buildTemplate(data: AutoData): PaneTemplate {
        val rowLimit = try {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
        } catch (_: Exception) {
            DEFAULT_ROW_LIMIT
        }

        val rows = buildRows(data).take(rowLimit)
        val pane = Pane.Builder().apply { rows.forEach { addRow(it) } }.build()

        return PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(config.appName))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    // 4 rows, always title-only, always same structure — every invalidate() is a refresh.
    private fun buildRows(data: AutoData): List<Row> = buildList {
        // Row 1: BG · trend · delta · time ago
        val bgLine = buildString {
            append("${data.bgText} ${data.trendSymbol}".trim())
            if (data.deltaText.isNotBlank()) append("  ·  ${data.deltaText}")
            if (data.timeAgoText.isNotBlank()) append("  ·  ${data.timeAgoText}")
        }.ifBlank { "--" }
        add(Row.Builder().setTitle(bgLine).build())

        // Row 2: IOB · COB · BR/TBR
        add(Row.Builder().setTitle("${data.iobText}  ·  ${data.cobText}  ·  ${data.basalDisplayText}").build())

        // Row 3: Target · Profile (merged to save space)
        val targetProfileLine = buildString {
            if (data.tempTargetText.isNotBlank()) append(data.tempTargetText) else append("--")
            append("  ·  ")
            if (data.profileText.isNotBlank()) append(data.profileText) else append("--")
        }
        add(Row.Builder().setTitle(targetProfileLine).build())

        // Row 4: Loop mode
        add(Row.Builder().setTitle(data.loopModeText.ifBlank { "--" }).build())
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L
        private const val AWAIT_INIT_TIMEOUT_MS = 5_000L
        private const val DEFAULT_ROW_LIMIT = 4
    }
}
