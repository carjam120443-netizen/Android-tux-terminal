# pkg sources

The catalog is data-driven. `pkg install <name>` resolves an entry in `packages.json` using this order:

1. `url` — direct HTTPS APK URL.
2. `source: "github"` + `repo: "owner/repository"` — latest GitHub Release APK asset.
3. `source: "fdroid"` — latest APK from the official F-Droid v2 index for the Android package ID.

## Adding future apps

Add an object like one of these to `pkg/resources/packages.json`:

```json
{"name":"Example","package":"com.example.app","description":"Example app","aliases":["example"],"url":"https://example.org/app.apk"}
```

or:

```json
{"name":"Example","package":"com.example.app","description":"Example app","aliases":["example"],"source":"github","repo":"owner/repository"}
```

or:

```json
{"name":"Example","package":"com.example.app","description":"Example app","aliases":["example"],"source":"fdroid"}
```

The app reads the catalog at runtime, so new entries can be added without rebuilding the terminal APK. Only HTTPS sources are accepted.

APK files are saved to Android Downloads on Android 10+ using MediaStore, then handed to Android's system package installer. The terminal cannot silently install apps or bypass Android's installation/security confirmation.
