package com.lmstudio.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lmstudio.client.ui.navigation.NavGraph
import com.lmstudio.client.ui.theme.LMStudioClientTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as LMStudioApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LMStudioClientTheme {
                NavGraph(
                    preferences = app.preferences,
                    chatRepository = app.chatRepository
                )
            }
        }
    }
}
