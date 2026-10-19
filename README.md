# Shelf

An Apple TV–style home screen for Fire TV, forked from
[dumbtv home](https://github.com/humblemuzzu/dumbtv) (MIT).

- **Top shelf:** your photo fills the top half of the screen. It's built into the app,
  so there's no file to copy onto the TV.
- **Frosted glass on every row:** the dock, each row of the app grid and the inputs row all
  sit on blurred, see-through panels that line up with the photo as you scroll.
- **Live sports scores:** slim score cards sit at the top left, on the same line as Options,
  Settings and the clock. They show live matches with the minute, full-time results and
  upcoming kickoff times, with team logos. When there are more games than fit, the cards
  turn to the next page every 8 seconds. You choose the leagues and your favorite teams in
  Options.
- **Score alerts over other apps:** while you're in YouTube, Prime Video or an HDMI input, a
  banner slides in at the top right for kick-off, goals, half-time and full-time in your
  favorite teams' games. It never takes over the remote.
- **Menu (☰, top right):** HDMI inputs, Options, Settings and updates, on a frosted panel.
- **Recently used:** your last apps, newest first, in a full-width row above the dock.
- **Weather by the clock:** temperature and a condition icon from Open-Meteo (no account).
  Pick the city and °C/°F in Options; defaults to Kingston, Jamaica.
- **tvOS-style focus:** the selected tile lifts with a real shadow and a light sheen, and
  its name appears underneath.
- **App grid:** press Down to scroll into the rest of your apps. The wallpaper dims as you
  go, and your HDMI inputs come last.
- **One permission: internet.** It's used only to fetch scores and team logos from ESPN's
  public sports data. No accounts, no tracking. Turn scores off in Options and nothing is
  fetched.

## Build and install (on your Mac)

One-time setup:

```sh
brew install openjdk@17
brew install --cask android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platforms;android-28" "build-tools;34.0.0"
```

Then, with the TV connected over ADB (`adb devices` shows `device`):

```sh
cd shelf
chmod +x build.sh install.sh
./install.sh            # builds the APK, installs it, takes over the Home button
```

To undo, run `./install.sh --undo`, which gives the Home button back to Amazon. To remove
Shelf entirely, also run `adb uninstall tv.shelf.home`.

## Updates without ADB

Every push to `main` on github.com/rlubsey/shelf builds a signed APK in GitHub Actions and
publishes it as a release (`build-<number>`). On the TV:

- An **Update** pill appears in the top bar when a newer build exists. Shelf checks once a day.
- Or open **☰ → Check for updates** at any time.
- If an update adds a permission (like the Recently used row did), run `./install.sh --setup`
  once from your Mac. It grants permissions to the installed Shelf without rebuilding.
- Choose **Install** and confirm Fire OS's screen. Your settings are kept.

One-time setup:
1. Add two repo secrets, `SHELF_KEYSTORE_B64` (`base64 -i release.keystore | pbcopy`) and
   `SHELF_KEYPASS` (`shelfhome`).
2. On the TV, go to Settings → My Fire TV → Developer Options → Install unknown apps and turn
   on **Shelf**.

Never lose `release.keystore`. It stays on your Mac and in the encrypted secret only. A build
signed with a different key can't update Shelf.

## Change the photo

Replace `res/drawable-nodpi/wallpaper.jpg` with any 1920×1080 JPG, then run `./install.sh`
again.

## Using it

| Remote | What it does |
|---|---|
| OK | Opens the app or switches to the input |
| Hold OK, or ☰ Menu | Move, Rename, Hide, App info |
| In Move mode | ◀ ▶ moves the tile one place, ▲ ▼ moves it a whole row (into or out of the dock), OK drops it |
| Up from the dock | Options, Settings |
| Back | Returns to the first tile |

**Options** has these settings:
- **Background:** Built-in photo (default), Image file, Black, Graphite, or Midnight
- **Image dim**
- **Tiles per row:** 4, 5 or 6
- **App names:** on focus, always, or never
- **Clock format**
- **Inputs row:** on or off
- **Sports scores:** shown or hidden
- **Score alerts in other apps:** favorite teams or off
- **Sports leagues:** Premier League, LaLiga, Serie A, Bundesliga, Ligue 1, MLS, Champions
  League, Europa League, FA Cup, Carabao Cup, Concacaf Champions Cup, NBA, NFL and MLB. The
  default is Premier League and Champions League.
- **Favorite teams:** for a league where you've picked favorites, the cards show only those
  teams' games, from yesterday's result through the next four days. Leagues without favorites
  show all of today's games.
- **Which apps appear:** apps without a TV banner (such as Downloader) start hidden. Tick
  them here to add them.

## Notes

- Scores refresh every minute while a match is live and every ten minutes otherwise, only
  while the home screen is on screen. ESPN's sports data is free but unofficial. If ESPN
  changes it, the cards say "No matches right now" and the rest of the launcher keeps working.

- Score alerts run inside Shelf's Home-button service, which is allowed to draw on top of
  other apps. Running `./install.sh --undo` turns that service off, so alerts stop too.
  Alerts only cover leagues where you've picked favorite teams, and never repeat goals
  scored before the TV was switched on.
- Shelf replaces dumbtv home's Home-button service. You can keep both installed, but only
  Shelf gets the Home button. To switch back, reinstall dumbtv home with its
  `launcher/install.sh`.
- The Home takeover uses dumbtv's accessibility-service method. This version recognizes
  Fire OS 8's home screen as well as Fire OS 7's. If Amazon's home still flashes or wins,
  run `./install.sh --undo`; nothing else is affected.
