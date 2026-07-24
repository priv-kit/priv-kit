package priv.kit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import priv.kit.sample.debug.PrivilegeSampleDebugController
import priv.kit.sample.debug.PrivilegeSampleDebugViewModel
import priv.kit.sample.startup.PrivilegeSamplePrivilegeUiCallbacks

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: PrivilegeSampleViewModel
    private lateinit var debugViewModel: PrivilegeSampleDebugViewModel
    private lateinit var debugController: PrivilegeSampleDebugController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[PrivilegeSampleViewModel::class.java]
        debugViewModel = ViewModelProvider(this)[PrivilegeSampleDebugViewModel::class.java]
        debugController = PrivilegeSampleDebugController(this, debugViewModel)
        setContent {
            val serverRunning by viewModel.serverRunning.collectAsState()
            PrivilegeSampleTheme(
                darkTheme = isSystemInDarkTheme(),
                dynamicColor = true,
            ) {
                PrivilegeSampleScreen(
                    serverRunning = serverRunning,
                    state = debugViewModel.screenState,
                    backStack = viewModel.backStack,
                    selectedDebugDestination = debugViewModel.selectedDebugDestination,
                    selectedStartupTab = debugViewModel.selectedStartupTab,
                    onOpenDebug = viewModel::openDebug,
                    onOpenPrivilegeUi = viewModel::openPrivilegeUi,
                    onDebugStarted = debugController::initialize,
                    onDebugStopped = debugController::dispose,
                    debugCallbacks = debugController.createCallbacks(
                        onOpenPrivilegeUi = viewModel::openPrivilegeUi,
                        onBackToHome = viewModel::navigateBack,
                    ),
                    privilegeUiCallbacks = PrivilegeSamplePrivilegeUiCallbacks(
                        config = (application as App).privilegeUiConfig,
                        back = viewModel::navigateBack,
                        connected = debugViewModel::handlePrivilegeUiConnected,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        debugController.onHostResumed()
    }

    override fun onDestroy() {
        debugController.dispose()
        super.onDestroy()
    }

}
