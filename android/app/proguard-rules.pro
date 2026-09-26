# Ed25519 kutuphanesi yansima kullanir
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# Room olusturulmus siniflari
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Media3
-keep class androidx.media3.** { *; }

# Cihaz yoneticisi alicisi manifest uzerinden cagrilir
-keep class com.otobusreklam.player.admin.AdminReceiver { *; }
-keep class com.otobusreklam.player.provision.ProvisionReceiver { *; }
