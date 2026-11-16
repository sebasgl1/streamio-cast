# R8 solo elimina código sin usar: sin ofuscar, así los informes de error muestran nombres reales.
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# libtorrent4j: el código nativo llama de vuelta a las clases Java (JNI/SWIG) por su nombre.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# Clases que Android o el Cast SDK crean por su nombre desde el manifiesto.
-keep class com.sebasgl1.streamiocast.cast.CastOptionsProvider { *; }

# Modelos serializados (kotlinx.serialization trae sus reglas; esto es por las dudas).
-keep @kotlinx.serialization.Serializable class com.sebasgl1.streamiocast.** { *; }

# FFmpegKit: el código nativo llama a estas clases Java por su nombre (JNI).
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**
