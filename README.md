# Tut's Trumpets

An Android app for playing the two trumpets from Tutankhamun's tomb, using notes
cut from the BBC's live broadcast of 16 April 1939.

- **Silver trumpet:** three notes (about 503, 767 and 991 Hz)
- **Copper trumpet:** three notes (about 475, 559 and 773 Hz) plus a rare low note (442 Hz)

Press and hold a note to play it; the note sustains while held and fades when
released. Slide between notes. Each trumpet plays one note at a time.
The info screen (the "i" button) plays the full 1939 broadcast and links to
the Wikipedia page and the BBC News article about the trumpets.

## Installing on your phone

Every push to `main` builds the app on GitHub and publishes it as the
**Latest build** release.

1. On your phone, open this repository's **Releases** page and tap
   `tut-trumpets.apk`.
2. Open the downloaded file. The first time, Android asks you to allow installs
   from your browser; allow it, then tap **Install**.
3. Later builds install over the previous one (the repo uses a fixed debug key).

## Building locally

Open the folder in Android Studio and press Run, or:

```
./gradlew assembleDebug
```

## Project layout

- `app/src/main/res/raw/bbc_1939_broadcast.mp3` – the full broadcast
- `app/src/main/assets/` – note samples (`*_sustain.wav`) and `meta.json` with loop points
- `AudioEngine.kt` – low-latency mixer with looping sustain and release fades
- `TrumpetView.kt` – the playing surface and multi-touch handling
- `MainActivity.kt` – app entry point and the info screen

## A note on rights

The samples come from a 1939 BBC broadcast. Check the rights to the recording
before publishing the app or making this repository public.

## Credits

- Trumpet photographs: Harry Burton, excavation photographs from the 1920s
  (Burton photos p0700 and P0227, Griffith Institute, University of Oxford).
  Public domain, via Wikimedia Commons:
  [silver trumpet](https://commons.wikimedia.org/wiki/File:Silver_trumpet_from_Tutankhamun%27s_tomb.jpg),
  [copper trumpet](https://commons.wikimedia.org/wiki/File:Tutankhamun%27s_bronze_trumpet.jpg).
- Audio: BBC broadcast from the Cairo Museum, 16 April 1939.
