# X5 Reframe

Insta360 X5 ile çekilmiş 360 videoları telefonda hızlıca kesip, kadrajlayıp (reframe) düz video olarak dışa aktaran kişisel Android uygulaması.

## Hedef
- GPU'da (OpenGL ES) fisheye → düz kadraj dönüşümü, ara 360 dosya üretmeden
- Kesme, klip birleştirme, açı/zoom anahtar kareleri
- Donanım kodlayıcıyla (MediaCodec) 16:9 / 9:16, 1080p / 4K export
- Dosyadaki jiroskop verisiyle sabitleme (ikinci aşama)

## Durum
Aşama 0: klip seçme ve dosya yapısını inceleme.

## APK
Her `main` push'unda GitHub Actions derler. Son APK: `builds` dalında `x5-reframe.apk`.
