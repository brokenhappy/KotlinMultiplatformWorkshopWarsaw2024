package com.woutwerkman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kmpworkshop.client.SliderGameClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SliderGameClient()
        }
    }
}
//
//@Preview
//@Composable
//fun AppAndroidPreview() {
//    App()
//}