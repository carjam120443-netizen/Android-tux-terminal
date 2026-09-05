# Android Tux `pkg`

`pkg` is the terminal's lightweight APK package helper.

## Commands

```text
pkg help
pkg sources
pkg update
pkg list
pkg install <package-name>
pkg install <https://example.org/app.apk>
```

The catalog lives at `pkg/resources/packages.json` and is fetched from this repository at runtime. Direct installs accept HTTPS APK URLs.

The app downloads the APK to its private cache and hands installation to Android's package installer. Android may require the user to allow this app to install unknown apps.

Only HTTPS downloads are accepted, and the downloader rejects files larger than 100 MB.
