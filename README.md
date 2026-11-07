# X5 Reframe

Insta360 X5 ile çekilmiş 360° videoları telefonda kadrajlayıp hızlıca düz (ya da 360°) video olarak dışa aktaran kişisel Android uygulaması. Tamamen cihazda ve internetsiz çalışır.

## Neden hızlı
Resmi uygulama önce tam 360° görüntüyü üretip sonra kadrajlar. X5 Reframe iki fisheye izini GPU'da (OpenGL ES 3) **doğrudan** çıktı kadrajına çevirir ve telefonun donanım kodlayıcısıyla (MediaCodec) yazar. Ses değişmediği sürece yeniden kodlanmadan kopyalanır.

## Arayüz
Alt gezinme: **Albüm · Projeler · Ayarlar**.
- **Albüm:** kamera klasöründeki çekimler tarihe göre 3 sütunlu ızgarada (360° rozeti, süre); Kamera / Dışa aktarılanlar; uzun basıp çoklu seçim → Birleştir ya da Oto Düzenle
- **Oynatıcı:** tam ekran 360°; solda Kes, Anahtar kare, Anlık görüntü, Öne çıkanlar, Favori, Sil (etiketler 3 sn sonra gizlenir); Serbest 360° / İleri / Selfie / Akıllı kadraj; kayıt modu (parmak ya da jiroskop); çift dokunma temiz ekran; Oto Düzenle · Düzenleyici · Kaydet
- **Düzenleyici:** ortası sabit kayan zaman şeridi (yakınlaştırma, savurma), anahtar kare düğmesi, yatay araç çubuğu: Kes, Görünüm, Derin takip, Anahtar kare, Renk, Çoklu açı, Sabitleme, Ses, Metin, Anlık görüntü, Dikiş
- **360 fotoğraf (.insp):** serbest bakış, Küçük gezegen / Tünel / 360°, oran, filtre, tam çözünürlükte kaydetme

## Özellikler
- **Dinamik dikiş:** dikiş çemberi bölgelere ayrılır, her bölgede iki lens arasındaki paralaks GPU'da blok eşleştirmeyle bulunur ve lensler bölgesel kaydırılır; lens renkleri eşitlenir (önizleme 48 bölge, dışa aktarım 192 × 5)
- **Anahtar kare eğrileri:** Doğrusal, Yumuşak giriş/çıkış, Yumuşak giriş, Gecikmeli geçiş, Yumuşak çıkış, Hızlı giriş/çıkış, Sert kesme; hareket şablonları (Küçük gezegen, Kahraman, Tavşan deliği, Yakınlaş)
- **Derin takip:** önerilen hedefler ya da elle çizilen kutu, şablon eşleştirme + ML Kit ile yeniden bulma, duraklat/devam, takip aralıkları zaman çizelgesinde
- **Renk:** 9 filtre (şiddet ayarlı) ve 9 ayar; GPU'da, önizleme = çıktı
- **Kadraj:** sürükle / iki parmakla yakınlaştır, anahtar kareler, önceki/sonraki kareye atlama
- **Görünüm (dewarp):** Doğrusal, Geniş (stereografik), Süper geniş (Pannini), Fisheye, 360° (Google Spherical bilgisiyle); Küçük gezegen, Tavşan deliği hazırları
- **Sabitleme:** 1 kHz jiroskop + ivmeölçer; Akıcı, Ufuk kilidi, Yön kilidi; şiddet ayarı
- **Çoklu açı:** Yan yana, Üst-alt, Resim içinde, Üçlü, Dörtlü; her bölmeye ayrı kadraj
- **Zaman çizelgesi:** birden çok klip, böl, çoğalt, sırala, hız (0,5×–4×), siyaha geçiş
- **Katmanlar:** metin (4 stil) ve görsel/logo; boyut, saydamlık, zaman aralığı, sıra
- **Ses:** müzik ekleme, seviyeler, döngü, yükselme/azalma; gerekirse 48 kHz AAC karışım
- **Otomatik düzenleme:** akıllı kadraj (kişileri takip), ortadakini takip et, öne çıkan anlardan kurgu (ML Kit, cihaz içi)
- **Dışa aktarım:** 16:9, 9:16, 1:1, 4:5, 2:1; 1080p / 2.7K / 4K; 24/25/30 fps; H.264 / H.265; bit hızı; boyut tahmini; arka planda ve kuyrukta, bildirimden takip
- **Projeler:** otomatik kayıt, son projeler, SD kart klasöründen medya listesi, hızlı önizleme için LRV

## Kurulum
Her `main` push'unda GitHub Actions APK'yı derler. Son sürüm: `builds` dalındaki `x5-reframe.apk`. APK sabit bir anahtarla imzalanır (`keystore/`), bu yüzden güncellemeler üstüne kurulur.

## Mimari
| Klasör | İçerik |
|---|---|
| `insv/` | Trailer ve protobuf okuyucu, `offset_v3` lens modeli (Mei + Brown), IMU kaydı |
| `gl/` | EGL, kadraj shader'ı (izdüşümler, dikiş, çoklu bölme), katman çizici |
| `media/` | Çift HEVC / LRV kaynakları, önizleme oynatıcı ve sesi, dışa aktarıcı, ses karıştırıcı, 360° bilgisi |
| `stab/` | Dördey matematiği, yönelim entegrasyonu ve sabitleme modları |
| `auto/` | Otomatik düzenleme (tarama, akıllı kadraj, öne çıkanlar, derin takip) |
| `album/` | Kamera klasörü tarama, küçük resim önbelleği |
| `photo/` | .insp 360 fotoğraf, tam çözünürlüklü tek kare çizici |
| `export/` | Dışa aktarım kuyruğu ve ön plan hizmeti |
| `project/` | Proje ve parça modeli, JSON kaydı |
| `ui/` | Compose arayüzü, zaman çizelgesi, parça şeridi |

## Doğrulama
- `tools/imu_calibrate.py`: jiroskop eksenlerini görüntü hareketinden ölçer (X5: 95 kare çiftinde medyan 0,19°)
- `app/src/androidTest`: örnek klip emülatörde dışa aktarılır; ses, 360° bilgisi, çoklu açı, sekans, karışım, katman, otomatik düzenleme ve ufuk kilidi test edilir; önizleme kare zamanlaması (PlaybackTest), dikiş hizalaması (StitchTest) ve ekran görüntüleri (ScreenshotTest)
- `tools/seam_*.py`: dikiş çözümlemesi (paralaks modeli doğrulaması)

## Teşekkür
Insta360 dosya yapısı ve lens modeli için [telemetry-parser](https://github.com/AdrianEddy/telemetry-parser) (MIT/Apache-2.0) belgelerinden yararlanıldı. Simgeler Material Icons (Apache-2.0).
