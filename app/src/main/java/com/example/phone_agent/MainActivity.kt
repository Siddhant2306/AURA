package com.example.phone_agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.phone_agent.ui.FridayAssistantScreen
import com.example.phone_agent.ui.theme.PhoneagentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneagentTheme {
                FridayAssistantScreen()
            }
        }
    }
}
