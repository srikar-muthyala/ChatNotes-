# ProGuard rules for ChatNotes
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.chatnotes.app.data.Models** { *; }
