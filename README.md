<img src="docs/logo.png" width="96" alt="Nota" />

# Nota

Music player for Android. It plays the songs already on your phone, and when you want something
else, it can fetch it. What it records about your listening stays on your own phone.

| Home | Now playing |
| --- | --- |
| ![Home](docs/home.jpg) | ![Now playing](docs/player.jpg) |

## What you get

- **Shelves built from your own counts.** Nota counts which songs you finish and which you cut
  short, and fills the home screen from those counts: what you have on repeat, compilations
  around an artist you play often, and that artist's newest release. The counting and the
  ranking both happen on the phone.
- **Songs that are not on the phone.** Search YouTube from inside the app and play the result,
  with the extraction done on the device.
- **Lyrics.** Nota looks them up, scrolls them with the song, and writes them in latin letters
  when the song is not.
- **Light on the connection.** Whatever Nota fetches once it writes to disk, so the same screen
  opens again without touching the network. Settings shows how much was spent and how much was
  saved.

## Next to the apps you know

|  | Nota | Spotify, YouTube Music, Apple Music | Other offline players |
| --- | --- | --- | --- |
| Account | not needed | required | not needed |
| Size on your phone | about a quarter of a megabyte | a hundred times that | ten to a hundred times that |
| Advertisements, subscription | neither | one or both | varies |
| Where suggestions come from | your own phone | their servers | there usually are none |
| Suggestions without a connection | yes | no | - |
| What it knows about you | stays on the phone | what you play, and when | varies |

It feels quick because it is small and keeps whatever it fetches. It opens straight into your
own library, and it goes back to the network only for something it does not have yet: a search
is kept for the rest of the day, a compilation for a week, an artist's picture for a month. So
the second visit to a screen is instant, and costs nothing on a slow or metered connection.

**Where the suggestions come from.** The big services suggest what millions of other people
played next. Nota suggests what *you* played next: it counts which songs you finish, which you
skip, which you go back to, and which two keep following each other, and it works all of that
out on the phone. That is the trade. It will not hand you a record that nobody like you has
heard yet, and nothing in it can be paid into your recommendations.

**What does leave the phone.** Everything Nota records about your listening sits in one database
on the phone, and that is the only copy. But finding a song, a cover, an artist's story or
lyrics means asking YouTube, MusicBrainz, Wikidata, LRCLIB or the Internet Archive, and those
requests carry what you searched for to them, over an IP address they can see. Your play counts
stay behind: no request includes them.

Nota owns no music and licenses none. It plays your files, and for anything else it asks the
public internet. So it is not a replacement for a subscription, and it cannot hand you a whole
catalogue the way one does.

---

## For developers

Plain Java against the framework, with a JDK and the Android command line tools as the only
things to install. `build.sh` drives `aapt2`, `javac`, `d8` and `apksigner` by hand. Code
and comments are English; the interface is translated.

### Building

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

### How a YouTube track is fetched

`YtStream` asks YouTube's own player endpoint from the phone, identifying as the client Apple's
headset app uses. That client is handed a plain, seekable address straight in the answer, ready
to open. Every other client either withholds the address or caps what it sends at about a
megabyte, which is a minute of a song.

`MediaPlayer` cannot read that address itself, so `StreamProxy` sits in front of it on
`127.0.0.1`: it fetches the audio in one-megabyte `Range` requests and feeds the player. The
chunking is what makes it usable: an open-ended request to the same URL is throttled to roughly
31 KB/s, about thirty times slower than a song plays. The proxy's URLs carry a token generated
fresh each run, so another app on the phone cannot use it as a downloader.

`Prefetch` fetches the next track ahead of time, so the wait lands on the first song rather than
on every skip.

## Licence

MIT, see [LICENSE](LICENSE). Nota is not affiliated with YouTube, MusicBrainz, LRCLIB or the
Internet Archive; it only asks their public endpoints.
