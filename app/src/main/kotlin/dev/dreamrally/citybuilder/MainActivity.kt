package dev.dreamrally.citybuilder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.dreamrally.citybuilder.ui.EditorScreen
import dev.dreamrally.citybuilder.ui.HomeScreen

class MainActivity : ComponentActivity() {
    private val vm: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF66BB6A), secondary = Color(0xFF4FC3F7), surface = Color(0xFF1F2F3B), background = Color(0xFF16222C))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (vm.city == null) HomeScreen(vm) else {
                        BackHandler { vm.closeProject() }
                        EditorScreen(vm)
                    }
                }
            }
        }
    }

    override fun onStop() { super.onStop(); vm.saveNow() }
}
