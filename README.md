<p align="center">
  <img src=".github/assets/k-kitsu-banner.png" alt="K-Kitsu Extensions banner" width="100%">
</p>

<h1 align="center">K-Kitsu Extensions</h1>

<p align="center">
  Anikku ve Aniyomi için özenle hazırlanan Türkçe anime kaynakları.
</p>

<p align="center">
  <a href="https://github.com/Koray-Ozt/k-kitsu-extensions/actions/workflows/build_push.yml"><img src="https://github.com/Koray-Ozt/k-kitsu-extensions/actions/workflows/build_push.yml/badge.svg" alt="Build durumu"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/lisans-Apache--2.0-7c3aed" alt="Apache 2.0 lisansı"></a>
  <img src="https://img.shields.io/badge/dil-Türkçe-06b6d4" alt="Türkçe">
</p>

## Hızlı kurulum

Anikku veya Aniyomi içindeki **Eklenti depoları** bölümüne aşağıdaki adresi ekleyin:

```text
https://raw.githubusercontent.com/Koray-Ozt/k-kitsu-repo/repo/index.min.json
```

Depoyu yeniledikten sonra istediğiniz kaynağı eklenti listesinden kurabilirsiniz.

## Kaynaklar

| Kaynak | Adres | Not |
|---|---|---|
| AnimeciX | [animecix.tv](https://animecix.tv) | Anime ve dizi kataloğu |
| Animpow | [animpow.com](https://animpow.com) | Şifreli API ve WebView yedeği |
| HentaiZM | [hentaizm1.com](https://www.hentaizm1.com) | Yalnızca yetişkinler için (18+) |
| TR Anime İzle | [tranimeizle.io](https://www.tranimeizle.io) | Türkçe anime kataloğu |
| Türk Anime TV | [turkanime.tv](https://www.turkanime.tv) | Gelişmiş filtreler ve çoklu video sağlayıcıları |

## İki repo, tek akış

- **Bu repo** eklentilerin Kotlin kaynak kodunu ve otomatik derleme iş akışını barındırır.
- **[k-kitsu-repo](https://github.com/Koray-Ozt/k-kitsu-repo/tree/repo)** imzalı APK dosyalarını, simgeleri ve Anikku katalog indeksini yayımlar.

Her eklenti sürümü artırıldığında GitHub Actions imzalı APK'ları üretir. Dağıtım reposundaki
`index.min.json`, uygulamanın güncellemeyi algılamasını sağlar.

## Proje yapısı

```text
src/tr/
├── animecix/
├── animpow/
├── hentaizm/
├── tranimeizle/
└── turkanime/
```

## Sorun bildirimi

Bir kaynak çalışmadığında mümkünse şu bilgileri ekleyin:

- Eklenti adı ve sürümü
- Anikku/Aniyomi sürümü
- Görünen hata mesajı veya HTTP kodu
- Sorunun listeleme, bölüm ya da video aşamasında olup olmadığı

Hesap şifresi, çerez veya erişim anahtarı gibi özel bilgileri issue içine yazmayın.

## Yasal not

Bu proje içerik barındırmaz ve listelenen sitelerle, Anikku ya da Aniyomi geliştiricileriyle bağlantılı
değildir. Eklentiler yalnızca üçüncü taraf sitelerde herkese açık olarak sunulan verilere istemci
arayüzü sağlar. Kullanım sorumluluğu kullanıcıya aittir.

Altyapı [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions) projesini temel alır ve
Apache License 2.0 koşulları altında geliştirilir.
