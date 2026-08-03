package android.app;

import android.content.Context;

/**
 * Compile-time stub for the framework's package-private {@code ContextImpl}.
 *
 * <p>This declaration is intentionally {@code public} only so callers can compile framework
 * methods whose descriptors expose {@code ContextImpl}, such as
 * {@code ActivityThread.getSystemContext()}. Do not emit a direct class access from product code,
 * for example with {@code ContextImpl.class} or Kotlin's {@code ContextImpl::class.java}. ART uses
 * the framework class's real visibility and can throw {@link IllegalAccessError}. Treat returned
 * instances as {@link Context}, and obtain the runtime class from the instance when needed.
 */
public abstract class ContextImpl extends Context {
}
