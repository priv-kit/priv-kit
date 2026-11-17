# The Shizuku server writes this binary class name to a Bundle.
-keep class moe.shizuku.api.BinderContainer {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Shizuku loads this endpoint from the host APK and calls its constructor.
-keep,allowoptimization,allowobfuscation class priv.kit.external.shizuku.internal.ShizukuStartService {
    public <init>();
}
