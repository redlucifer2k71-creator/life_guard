# Retrofit / Gson
-keep class com.google.gson.** { *; }
-keep class com.squareup.retrofit2.** { *; }
-keep class com.squareup.okhttp3.** { *; }

# Life Guard App
-keep class com.lifeguard.app.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }

# Google Play Services
-keep class com.google.android.gms.** { *; }

# Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep annotations
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Keep parcelable creators
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Keep enum values
-keepclassmembers enum * {
    **[] $VALUES;
    public *;
}

# Room (if used)
-keep class androidx.room.** { *; }