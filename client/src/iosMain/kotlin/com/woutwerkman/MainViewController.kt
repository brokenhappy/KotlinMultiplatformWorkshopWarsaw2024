package com.woutwerkman

import androidx.compose.ui.window.ComposeUIViewController
import kmpworkshop.client.ClientEntryPoint

fun MainViewController() = ComposeUIViewController {
    ClientEntryPoint()
}