# ProGuard / R8 rules for :app
#
# ## These rules are currently INACTIVE
#
# `app/build.gradle.kts` sets `isMinifyEnabled = false` for the release build, and the comment
# there explains why: plan.md G1 makes this app free to build with no configuration, and an R8
# failure (a stripped reflection entry point, a renamed Hilt or Room generated class) is invisible
# until a release APK is *installed*. The build machine has no Android SDK, so that class of
# failure could not be caught before shipping. The APK is a few hundred KB larger; that is the
# price, and it is deliberate rather than an oversight.
#
# ## What these rules are for
#
# The day real signing exists and R8 is switched on, these are the rules that would be needed.
# They are written now rather than at that moment, because discovering a missing keep rule by
# shipping a broken APK is the worst way to find out.
#
# ## Where each rule comes from
#
# `docs/VERIFICATION.md` records that this app has **never been compiled or minified** — there is no
# Android SDK on the machine it was written on. So these rules are written from each library's
# documented requirements, not from observed behaviour, and the first real minified build is the
# first real test of them. Treat that build as a verification step, not a formality.

# ---------------------------------------------------------------------------------------------
# kotlinx.serialization
#
# The generated `Companion.serializer()` is reached reflectively by the runtime, and the generated
# `$serializer` classes are looked up by name. Without this, every `@Serializable` type silently
# fails at runtime with "Serializer for class 'X' not found" — a crash, not a build error, so R8
# cannot warn about it.
# ---------------------------------------------------------------------------------------------
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}

# ---------------------------------------------------------------------------------------------
# Room
#
# The generated `MiaDatabase_Impl` is instantiated by name from the `@Database` class, and the
# generated DAO implementations are constructed reflectively too.
# ---------------------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------------------------
# Hilt / Dagger
#
# Hilt's generated components are instantiated from `MiaApplication` by name. The `@HiltAndroidApp`
# annotation processor keeps most of this already, but the base `Application` reference is resolved
# reflectively at startup, so a stripped build fails on launch rather than at compile time.
# ---------------------------------------------------------------------------------------------
-keep class dagger.hilt.android.HiltAndroidApp { *; }
-keep class com.mia.app.MiaApplication { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-dontwarn dagger.hilt.**

# ---------------------------------------------------------------------------------------------
# WorkManager
#
# `EodRefreshWorker` and `HistoryBackfillWorker` are constructed reflectively by the
# `HiltWorkerFactory` from their class name and constructor signature. Both are referenced by name
# only — no code in the app names them — so without this they are prime R8 removal candidates and
# the 19:00 IST refresh silently stops happening.
# ---------------------------------------------------------------------------------------------
-keep class * extends androidx.work.CoroutineWorker { <init>(...); }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# ---------------------------------------------------------------------------------------------
# kotlinx.coroutines
#
# `DebugProbes` and the service-loader lookups are absent from a release build anyway; this only
# silences the resulting warnings.
# ---------------------------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.debug.**

# ---------------------------------------------------------------------------------------------
# jsoup (transitively, via :data)
#
# `:data` declares jsoup as `implementation`, so it is on the runtime classpath but not the
# compile classpath. R8 cannot see the annotations it reflects over.
# ---------------------------------------------------------------------------------------------
-dontwarn org.jsoup.**
-keep class org.jsoup.nodes.** { *; }

# ---------------------------------------------------------------------------------------------
# Line numbers
#
# `isMinifyEnabled = false` means these have no effect today. They are here so that enabling R8
# does not also throw away every stack trace the app will ever produce — which matters for an app
# whose failure messages are part of its UI contract (plan.md §8.3, plan.md §12).
# ---------------------------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile