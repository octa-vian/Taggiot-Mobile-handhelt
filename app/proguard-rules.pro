# Room
-keep class * extends androidx.room.RoomDatabase
# Retrofit/Gson DTO
-keep class com.taggiot.handheld.net.dto.** { *; }
# SEUIC SDK (dipanggil lewat adaptor)
-keep class com.seuic.** { *; }
-dontwarn com.seuic.**
-keep class com.taggiot.handheld.reader.seuic.** { *; }
