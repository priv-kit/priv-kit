package priv.kit.ui.m3

import android.view.ViewTreeObserver
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Binds M3 hint visibility. Business authorization has no knowledge of window focus. */
@Composable
public fun PrivilegeM3Presentation(
    viewModel: PrivilegeM3ViewModel,
) {
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, view, owner) {
        fun update() = viewModel.updateHostVisibility(
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), view.hasWindowFocus())
        val observer = LifecycleEventObserver { _, _ -> update() }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { update() }
        owner.lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        update()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
        }
    }
}
