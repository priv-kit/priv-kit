package priv.kit.sample

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (AndroidTarget.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }
}

private object AndroidTarget {
    val P: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}
