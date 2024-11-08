@file:Suppress("FunctionName")
@file:OptIn(ExperimentalFoundationApi::class)

package kmpworkshop.client

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
fun PressiveGame() {
    var hint by remember { mutableStateOf("Connecting to host...") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        getFlowOfPressiveGameHints().collect { newHint ->
            hint = newHint
        }
        if ("My pressing state" == "single press") doSinglePress()
        if ("My pressing state" == "double press") doDoublePress()
        if ("My pressing state" == "Long pressing") doLongPress()
    }
    Row(
        modifier = Modifier.combinedClickable(
            onClick = { println("Did single click!") },
            onDoubleClick = { println("Did double click!") },
            onLongClick = { println("Did long click!") }
        )
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Column {
            Spacer(modifier = Modifier.weight(1f))
            Text(hint)
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}