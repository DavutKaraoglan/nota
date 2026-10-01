<img src="docs/logo.png" width="96" alt="Nota" />

# Nota

A music player for Android. It plays the songs that are already on your phone, and when you
want something else, it can go and find it. No account to make, no advertisements, no monthly
fee, and no profile of you kept anywhere but on your own phone.

| Home | Now playing |
| --- | --- |
| ![Home](docs/home.jpg) | ![Now playing](docs/player.jpg) |

## What you get

- **Your own music.** Songs, albums, artists and folders, found by themselves. Controls on the
  lock screen, an equaliser, a sleep timer, playlists and favourites.
- **A home screen that learns.** What you play, replay and skip quietly builds up, and the home
  screen fills with your own shelves: what you have on repeat, compilations around an artist you
  like, and the newest thing they have out.
- **Discover.** Search for a song or an artist and play it, without leaving the app.
- **Lyrics.** Found on their own and scrolling along with the song, in latin letters even when
  the song is not.
- **Light on the connection.** Anything fetched once is kept, so opening the same screen again
  is instant and free. Settings tells you how much was spent and how much was saved.

The app speaks English and Turkish.

## Next to the apps you know

|  | Nota | Spotify, YouTube Music, Apple Music | Other offline players |
| --- | --- | --- | --- |
| Account | not needed | required | not needed |
| Size on your phone | about a quarter of a megabyte | a hundred times that | ten to a hundred times that |
| Advertisements, subscription | neither | one or both | usually a one-off payment |
| Where suggestions come from | your own phone | their servers | there usually are none |
| Suggestions without a connection | yes | no | — |
| What it knows about you | stays on the phone | what you play, and when | varies |

**Why it feels quick.** It is a small app that carries nothing it does not use. It does not
report to anyone when it opens, and it does not go back for something it already has: a search
is kept for the rest of the day, a compilation for a week, an artist's picture for a month. So
the second visit to a screen is instant, and costs nothing on a slow or metered connection.

**Where the suggestions come from.** The big services suggest what millions of other people
played next. Nota suggests what *you* played next: it notices which songs you finish, which you
skip, which you go back to, and which two keep following each other, and it works all of that
out on the phone. That is an honest trade. It will not hand you a record that nobody like you
has heard yet — and it will not push something because someone paid for it to be pushed.

**What does leave the phone.** Nota has no server and keeps no account, so there is nowhere for
a profile of you to live. But finding a song, a cover, an artist's story or a set of lyrics means
asking YouTube, MusicBrainz, Wikidata, LRCLIB or the Internet Archive, and those requests carry
what you searched for to them, over an IP address they can see. What you play, skip and replay
is never part of that: it stays in a database on the phone.

**What it is not.** Nota owns no music and licenses none. It plays your files, and for anything
else it asks the public internet. So it is not a replacement for a subscription, and it cannot
hand you a whole catalogue the way one does.

---

## For developers

No Gradle, no AndroidX, no third-party libraries: plain Java against the framework, built by a
shell script that drives `aapt2`, `javac`, `d8` and `apksigner` by hand. Code and comments are
English; the interface is translated.

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

There is no server in the middle: `YtStream` asks YouTube's own player endpoint, identifying as
the client Apple's headset app uses. That client is handed a plain, seekable address straight in
the answer — no signature puzzle, no attestation token, and no hidden `WebView` to drive. Every
other client either withholds the address or caps what it sends at about a megabyte, which is a
minute of a song.

`MediaPlayer` cannot read that address itself, so `StreamProxy` sits in front of it on
`127.0.0.1`: it fetches the audio in one-megabyte `Range` requests and feeds the player. The
chunking is not decoration — an open-ended request to the same URL is throttled to roughly
31 KB/s, about thirty times slower than a song plays. The proxy's URLs carry a token generated
fresh each run, so another app on the phone cannot use it as a downloader.

`Prefetch` fetches the next track ahead of time, so the wait lands on the first song rather than
on every skip.

## Licence

MIT, see [LICENSE](LICENSE). Nota is not affiliated with YouTube, MusicBrainz, LRCLIB or the
Internet Archive; it only asks their public endpoints.
