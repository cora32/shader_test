package io.iskopasi.shader_test

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.createFontFamilyResolver
import io.iskopasi.shader_test.ui.composables.LeftSide
import io.iskopasi.shader_test.ui.composables.RightSide
import io.iskopasi.shader_test.ui.theme.Shader_testTheme
import io.iskopasi.shader_test.utils.Shaders
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    private val controller: DrawerController by viewModels()

    // Font loader debugger
    private val handler = CoroutineExceptionHandler { _, throwable ->
        Log.e("shader_test", "There has been an issue: ", throwable)
    }
    private lateinit var drawerState: DrawerState
    private lateinit var scope: CoroutineScope

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun onShaderClicked(shader: Shaders) {
        scope.launch {
            controller.onShaderClick(shader)
            drawerState.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            scope = rememberCoroutineScope()

            Shader_testTheme {
                // A surface container using the 'background' color from the theme
                CompositionLocalProvider(
                    LocalFontFamilyResolver provides createFontFamilyResolver(
                        LocalContext.current,
                        handler
                    )
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ModalNavigationDrawer(
                            drawerState = drawerState,
                            drawerContent = { LeftSide(::onShaderClicked) }) {
                            Box(modifier = Modifier
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                    }
                                }) { RightSide() }
                        }
                    }
                }
            }
        }
    }
}