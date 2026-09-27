# ---------------------------------------------------------------------------
# SAHA YIGIN IZLERI OKUNABILIR OLMAK ZORUNDA.
#
# Bu cihazlara uzaktan baglanamiyoruz; elimizdeki tek teshis verisi cihazin
# gonderdigi hata metni ve logcat'te kalan yigin izi. R8 varsayilan olarak satir
# numarasi tablosunu ATAR: "bir yerde NullPointerException" gibi hicbir ise
# yaramayan bir iz kalir ve otobuse gidilmesi gerekir.
#
# mapping.txt derleme ciktisinda uretilir (build/outputs/mapping/release/) ve
# YAYINLANAN HER SURUM ICIN SAKLANMALIDIR - kaybedilirse o surumden gelen
# izler bir daha cozulemez. CI bunu yapi ciktisi olarak arsivliyor.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SRC

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
