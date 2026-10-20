-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# ML Kit (barcode scanning) needs its internal classes intact under R8 full mode
-keep class com.google.mlkit.common.** { *; }
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.mlkit.vision.barcode.internal.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.mlkit.vision.text.internal.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }

