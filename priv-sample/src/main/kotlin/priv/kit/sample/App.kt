package priv.kit.sample

import android.app.Application
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import priv.kit.sample.startup.createPrivilegeSampleUiConfig
import priv.kit.ui.PrivilegeUi

class App : Application() {
    private val automaticRecoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val privilegeUiConfig by lazy {
        createPrivilegeSampleUiConfig(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    override fun onCreate() {
        super.onCreate()
        automaticRecoveryScope.launch {
            PrivilegeUi.startSilentlyIfEnabled(
                context = this@App,
                config = privilegeUiConfig,
            )
        }
    }
}
