package app.aaps.core.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.keys.interfaces.TextRef

@Preview(showBackground = true)
@Composable
internal fun StepButtonsPreview() {
    MaterialTheme {
        ExpandableRowHeader(
            label = "Correction",
            expanded = false,
            onExpand = {},
            trailingContent = {
                StepButtons(
                    label = "Correction",
                    value = 0.5,
                    onValueChange = {},
                    valueRange = -10.0..10.0,
                    step = 0.1,
                    unitLabel = TextRef.Literal("U"),
                    decimalPlaces = 2
                )
            }
        ) {
            Text("+0.50 U")
        }
    }
}
