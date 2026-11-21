package priv.kit.ui

/** Custom UI contracts may change between releases without source or binary compatibility. */
@RequiresOptIn(
    message = "Privilege UI customization APIs do not guarantee source or binary compatibility.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalPrivilegeUiApi
