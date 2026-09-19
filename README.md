<img src="docs/logo.png" width="96" alt="Nota" />

# Nota

An Android music player for the audio already on the phone, with a Discover tab for everything
else. No Gradle, no AndroidX, no third-party libraries: the app is plain Java against the
framework, built by a shell script that drives `aapt2`, `javac`, `d8` and `apksigner` by hand.

| Home | Now playing |
| --- | --- |
| ![Home](docs/home.jpg) | ![Now playing](docs/player.jpg) |

## What it does

- **Library** — tracks, albums, artists and folders from MediaStore, with a background service,
  lock-screen controls and an equalizer.
- **Lyrics** — a `.lrc` or `.txt` file next to the audio file, otherwise [LRCLIB](https://lrclib.net).
  Timed lines follow playback; the current one also appears above the title in the player.
- **Artist info** — the latin reading of a non-latin stage name, the legal name behind an alias
  and the one-line description [MusicBrainz](https://musicbrainz.org) keeps.
- **Discover** — searching and playing from YouTube and the Internet Archive.
- **Data saver** — every network answer is cached on disk with its own freshness window, and the
  app reports what it spent.

Interface strings are English and Turkish; code and comments are English.

## Next to the usual players

|  | Nota | Streaming apps (Spotify, YouTube Music, Apple Music) | Offline players (Poweramp, Musicolet) |
| --- | --- | --- | --- |
| Account | none | required | none |
| Download | 223 KB | tens of megabytes | a few to tens of megabytes |
| Paid tier, ads | neither | one or both | usually a one-off unlock |
| Where suggestions come from | this phone | their servers | mostly nowhere |
| Suggestions with the network off | yes, from what was played | no | — |
| Analytics, crash reporting | none | several | varies |
| Permissions | 8, all of them named in one screen of manifest | dozens, plus Play Services | a handful |

**Performance.** The difference is what is not there: no Play Services, no analytics SDK, no
sync on launch, no image or networking library. One dex file starts the app and MediaStore
fills the library; everything that touches the network is spelled out in `src/com/nota/data`
and is asked for only when a screen needs it. Every answer is cached on disk with a freshness
window of its own — six hours for a search, a week for a mix, a month for an artist's banner —
so opening the same screen twice costs nothing, and the settings screen reports what was spent
and what came from cache.

**Suggestions.** A streaming service recommends from what millions of people played next;
`Recommender` recommends from what *you* played next. Skips, replays, completions and the pair
of songs that followed each other are scored on the device, and the one outside opinion it asks
for is the radio the catalogue builds around a song. That is the honest trade: it will not
surface a record nobody you resemble has heard, and it will not push what a chart is paid to
push. Nothing about a listener leaves the phone to make it work.

**What it is not.** It has no licence to anything. It plays the files on the phone, and for the
rest it asks public endpoints through a helper you host yourself — so it is not a replacement
for a subscription, and it cannot hand you a catalogue the way one does.

## Building

You need a JDK 21, the Android command line tools that Termux packages, and one Google-licensed
binary the repository cannot carry:

```sh
pkg install openjdk-21 aapt2 d8 apksigner zip     # Termux
cp .../platforms/android-33/android.jar tools/android33.jar
./build.sh
```

The script generates a debug keystore on the first run and leaves the signed APK in
`build/nota.apk`. The platform jar can also be pointed at from outside:

```sh
ANDROID_JAR=/path/to/android.jar ./build.sh
```

## How a YouTube track is fetched

A helper does the extraction, because doing it on the phone means driving a hidden `WebView`
through YouTube's own player to get past its attestation check, and that costs seconds and
battery on every track. `notastream` is a stdlib Python service on a small box; the app asks
it for a video id and gets audio back.

The app never holds a stream URL. It signs `v=<id>&e=<expiry>` with a secret shared only with
the helper, and the helper refuses anything unsigned or expired. So its address is not a
capability: knowing where it lives does not let anyone feed it ids of their own.

The helper's certificate is its own. The app ships a copy as the single trust anchor for that
address, which means no public authority can vouch for something standing in its place, and
nothing may answer in the clear.

A track is written to disk before it plays, and `Prefetch` fetches ahead, so the wait lands on
the first song rather than on every skip.

The address, the secret and the certificate belong to one deployment, so none of them is in the
tree. `build.sh` puts example copies in when they are missing: a clone builds and runs, and only
the YouTube half stays quiet. Pointing a build at your own helper is three files:

```sh
./tools/gen_backend.py https://your-host:8443 "$(cat ../notastream/secret)"
openssl s_client -connect your-host:8443 </dev/null 2>/dev/null \
  | openssl x509 > res/raw/notastream.pem
$EDITOR res/xml/network_security_config.xml   # <domain> is the helper's address
```

## Licence

MIT, see [LICENSE](LICENSE). Nota is not affiliated with YouTube, MusicBrainz, LRCLIB or the
Internet Archive; it only asks their public endpoints.
