<img src="docs/logo.png" width="96" alt="Nota" />

# Nota

A music player for Android. It plays the songs that are already on your phone, and when you
want something else, it can go and find it. No account to make, no advertisements, no monthly
fee, and nothing about you is sent anywhere.

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
| What it knows about you | nothing leaves the phone | what you play, and when | varies |

**Why it feels quick.** It is a small app that carries nothing it does not use. It does not
report to anyone when it opens, and it does not go back for something it already has: a search
is kept for the rest of the day, a compilation for a week, an artist's picture for a month. So
the second visit to a screen is instant, and costs nothing on a slow or metered connection.

**Where the suggestions come from.** The big services suggest what millions of other people
played next. Nota suggests what *you* played next: it notices which songs you finish, which you
skip, which you go back to, and which two keep following each other, and it works all of that
out on the phone. That is an honest trade. It will not hand you a record that nobody like you
has heard yet — and it will not push something because someone paid for it to be pushed.

**What it is not.** Nota owns no music and licenses none. It plays your files, and for anything
else it asks the public internet — through a small helper you have to run yourself. So it is not
a replacement for a subscription, and it cannot hand you a whole catalogue the way one does.

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
</content>
</invoke>
