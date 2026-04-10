# Proguard rules for OpenClaw PoC

# Keep NodeRunner (used reflectively from process callbacks)
-keep class ai.openclaw.poc.NodeRunner { *; }

# Keep DeviceControlApi (HTTP server handlers)
-keep class ai.openclaw.poc.DeviceControlApi { *; }
-keep class ai.openclaw.poc.DeviceControlApi$* { *; }

# Keep GatewayService (foreground service)
-keep class ai.openclaw.poc.GatewayService { *; }

# Keep models used by Gson/JSON
-keep class ai.openclaw.poc.GatewayState { *; }
-keep class ai.openclaw.poc.ModelProviderConfig { *; }

# Keep all Fragment classes (instantiated by Android)
-keep class ai.openclaw.poc.*Fragment { *; }

# Keep MainActivity
-keep class ai.openclaw.poc.MainActivity { *; }

# Keep all Activity classes
-keep class ai.openclaw.poc.*Activity { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enum classes
-keepclassmembers enum * { *; }

# Keep R class
-keep class **.R$* { *; }

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Kotlin metadata
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
