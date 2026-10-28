# J.A.R.V.I.S. — kırmızı temalı Türkçe sesli asistan (Android)

Gerçek bir Android uygulaması: telefona kurulur, ana ekranda kırmızı JARVIS
simgesiyle açılır, "efendim" diye hitap eder.

## APK'yı indir / kur

1. [Releases](https://github.com/boomys/jarvis/releases) sayfasından `JARVIS.apk` indir
   (yoksa **Actions** sekmesindeki son `APK derle` çalışmasının artifact'ını indir).
2. Telefonda dosyayı aç → "bilinmeyen kaynaktan kurulum" izni ver → kur.
3. İlk açılışta mikrofon/kamera/konum izinlerini ver.

## Uygulamada ne var

- Kırmızı HUD arayüzü: dönen halkalar, nabız gibi atan çekirdek (`ReactorView`).
- Türkçe sesli komut (`SpeechRecognizer`, tr-TR) ve Türkçe sesli yanıt (`TextToSpeech`).
- Her cevapta **"efendim"** hitabı; ayarlardan açılan **"Jarvis" uyandırma sözcüğü**
  (adını söyleyince "Efendim?" der ve komut bekler).
- **Kamera** (fotoğraf çekme), **flaş/fener** (Camera2 torch), **konum** (+haritada açma),
  **pil**, **saat/tarih**, **notlar** (kalıcı), **arama/SMS**, **şaka**, **yardım**.
- **Uygulama açma:** WhatsApp, Instagram, YouTube, Spotify, Telegram, TikTok, Gmail,
  Haritalar, Chrome, Ayarlar — kuruluysa gerçek uygulama açılır, değilse web sürümü.
- Komut motoruna uymayan her şey AI beynine gider (sunucudaki Worker üzerinden).

## Komut örnekleri

```
WhatsApp'ı aç
kamerayı aç        fotoğraf çek        flaşı aç
saat kaç           pil durumu          konumum neresi
not al: yarın doktor randevusu
notlarım           yardım              şaka yap
```

## Derleme

Gradle gerekmez; araç zinciri doğrudan çağrılır:
`aapt2 compile/link` → `javac --release 11` → `d8` → `zipalign` → `apksigner`.

```bash
# yerelde: JDK 17 ve Android SDK (build-tools 34.0.0, platforms/android-34) gerekir
ANDROID_HOME=/yol/android-sdk bash android/build-apk.sh

# ya da bu depodaki tools/ düzenini kullan (JDK + SDK tools/ altında)
bash android/build-apk.sh
```

Çıktı: `android/JARVIS.apk`. Debug anahtarıyla imzalanır (`android/debug.keystore`
iken yoksa üretilir). Play Store için kendi keystore'unla imzala.

CI: `.github/workflows/build-apk.yml` her `main` push'unda APK derleyip artifact yükler.

## AI beyni

APK, AI yanıtları için `https://site-2c429ebe943e42499156764fc0b8ff73.freebuff.page/api/chat`
adresini kullanır (bu depodaki `worker.js`). Serbest sohbet için o Worker'a
`OPENAI_API_KEY` tanımlanmalıdır. Anahtar yokken de tüm cihaz komutları çalışır;
`MainActivity.java` içindeki `AI_URL` sabitini kendi sunucunla değiştirebilirsin.

## Sınırlar

- WhatsApp'ta otomatik mesaj okuma/yazma Android'de normal uygulamalara kapalıdır;
  bu sürüm WhatsApp'ı açar, arama ve SMS başlatır.
- Fotoğraf, sistem kamera uygulaması üzerinden çekilir ve sohbete küçük hâliyle düşer.
- APK debug anahtarıyla imzalıdır; Play Store dağıtımı için yeniden imzalanmalıdır.

## Dosyalar

```
android/AndroidManifest.xml                     izinler, launcher, queries
android/src/com/boomys/jarvis/MainActivity.java komut motoru, izinler, AI, arayüz
android/src/com/boomys/jarvis/ReactorView.java  kırmızı HUD çizimi
android/build-apk.sh                            APK derleme betiği
worker.js                                       AI proxy (Cloudflare Worker)
scripts/gen-icons.mjs                           ikon üretici (bağımlılıksız PNG)
```
