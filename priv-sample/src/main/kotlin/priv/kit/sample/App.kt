package priv.kit.sample

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import priv.kit.sample.ui.createPrivilegeSampleUiConfig

class App : Application() {
    internal val privilegeUiConfig by lazy {
        createPrivilegeSampleUiConfig(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }
}
