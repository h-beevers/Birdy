package com.henrybeevers.birdy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.henrybeevers.birdy.ui.BirdyRoot
import com.henrybeevers.birdy.ui.theme.BirdyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BirdyTheme {
                BirdyRoot()
            }
        }
    }
}
