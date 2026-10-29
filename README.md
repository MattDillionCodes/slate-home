# Slate Home

A tablet-style home screen for Google TV and Android TV, built with Jetpack Compose and
designed around the remote: widgets on the left, your apps in a grid, a glass dock below.

> The design takes cues from Apple's iPadOS. Slate Home is not affiliated with or endorsed by
> Apple; iPad and iPadOS are trademarks of Apple Inc. All artwork in this app (wallpapers,
> glass effects) is original and drawn in code.

## Features

- **Home screen** — clock, calendar and 5-day weather widgets, a 4×3 app grid, and a dock with
  favourites, recent apps and the App Library. Only apps that are actually installed appear;
  preinstalled system utilities stay in the App Library.
- **Edit mode** — hold OK on any app: icons jiggle, OK picks one up, the arrows move it (down past
  the last row drops it into the dock), hold OK to remove it, “+ Add App” to add one, Reset to go
  back to the defaults. Your layout is saved.
- **Control Center** — Wi-Fi/Bluetooth/Display/Accessibility shortcuts, a real volume slider,
  mute, StandBy, TV Inputs (the TV's own input picker), Wallpaper and Settings. Opens from the
  top-right button or the remote's Menu key.
- **Wallpapers** — six built-in looks (Aurora, Dunes, Bloom, Ribbons, Horizon, Pastel), or your own
  photos: send them from your phone by scanning a QR code, or pick them from the TV.
- **StandBy** — a big clock with date and weather after 5 idle minutes on the home screen.
- **Search** — opens Google TV's search, when the TV has it.
- **Fast on TV hardware** — static decoration is cached in GPU layers and the glass blur is
  precomputed, so focus animations run at ~60 fps even on low-end TV chips.

## Requirements

- Android TV / Google TV device or emulator, Android 8.0 (API 26) or newer
- To build: JDK 17+ and the Android SDK (Android Studio works out of the box)

## Build and install

```bash
./gradlew assembleRelease
adb connect <your-tv-ip>:5555        # enable Developer options → USB/Wireless debugging first
adb install -r app/build/outputs/apk/release/app-release.apk
```

Open **Slate Home** from your TV's app list.

### Signing

Without extra setup, release builds are signed with your local debug key, which is fine for your
own TV. To sign builds you hand to other people, create a keystore and a git-ignored
`keystore.properties` in the project root:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

### Making it your Home button

The app registers as a home screen, but Google TV keeps its own launcher on the Home button.
Some people switch Google's launcher off over ADB; this can break Google TV features and is
reversible with `pm enable`. Do this at your own risk.

## Privacy

- **Weather**: the TV's approximate city is looked up from its public IP address via
  [GeoJS](https://www.geojs.io/), then the forecast is fetched from
  [Open-Meteo](https://open-meteo.com/). Both receive your IP address. To skip the IP lookup, set
  `PINNED_PLACE` in `WeatherRepository.kt`.
- **Add from Phone**: while that screen is open, the TV runs a small web server on your local
  network. Its address contains a random one-time code, it only accepts images, and it stops as
  soon as you close the screen. Nothing is sent to the internet.
- No analytics, accounts or tracking. Your layout, recents and photos stay on the TV.

## Credits

- Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0)
- Location lookup by [GeoJS](https://www.geojs.io/)
- QR codes by [ZXing](https://github.com/zxing/zxing) (Apache 2.0)
- Icons from Material Symbols (Apache 2.0)

## License

[MIT](LICENSE)
