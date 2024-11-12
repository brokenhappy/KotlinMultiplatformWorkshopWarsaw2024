package com.woutwerkman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kmpworkshop.client.ClientEntryPoint

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ClientEntryPoint()
        }
    }
}
//
//@Preview
//@Composable
//fun AppAndroidPreview() {
//    App()
//}