# TapHive release rules.

# Custom views are inflated from XML by name.
-keep class com.taphive.app.PieChartView { *; }

# Keep the application entry points referenced by the manifest.
-keep class com.taphive.app.TapHiveApp { *; }
-keep class com.taphive.app.MainActivity { *; }
-keep class com.taphive.app.TrafficActivity { *; }
-keep class com.taphive.app.SettingsActivity { *; }
-keep class com.taphive.app.AboutActivity { *; }
-keep class com.taphive.app.CaptureService { *; }
-keep class com.taphive.app.BootReceiver { *; }
