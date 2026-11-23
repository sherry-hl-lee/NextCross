package com.fit5120tp10.nextcross

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.fit5120tp10.nextcross.ui.NextCrossApp
import com.fit5120tp10.nextcross.ui.theme.NextCrossTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NextCrossTheme {
                NextCrossApp()
            }
        }
    }
}
