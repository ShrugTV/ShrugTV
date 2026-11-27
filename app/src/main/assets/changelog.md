<!-- Generated from CHANGELOG.md by tools/changelog_app.py; do not edit by hand. -->

## 2.5.0 – 2026-11-27

### New
- Home row "Top streams in your language" (Settings → Home → Rows, off by default); the switch for it existed but showed no row.
- Phone companion: search also finds categories; a category lists its live channels, and a channel card opens its recordings and clips to start on the TV.
- Phone companion: the Version tab shows a short view of the watch statistics (today and the last 7 days).
- Clips page: the setting "Play the focused clip" (Settings → Home → Clips, off by default) runs the focused clip muted in the stage, charts and Clip TV views; Clip TV then plays the channel clip after clip by itself.
- "Highlights" in the player bar of a live stream: the five most viewed clips since the stream started; OK plays one, Back returns to live (setting under Player → Controls).
- Phone page: type on the TV (the text field of the Remote tab writes into the field that has the focus on the TV, e.g. the search), open a Twitch link or a channel name on the TV, rewind, fast forward and captions buttons, a position slider for recordings, held arrow buttons repeat, and on a computer the arrow keys, Enter, Backspace and Space steer the TV.
- When a recording ends, the next recording of the same channel starts after a 15-second countdown (the next newer broadcast, after the newest one the one before; recordings watched to 90 % are skipped). It is preloaded during the countdown; OK starts it at once, Back stays. Setting "Next recording at the end" in the player settings, group "Recordings".
- Recordings you watched to 90 % or more are dimmed on the channel page and in the home rows, like opened clips; Settings → Home → "Recordings I watched" shows them normally, dims them (default) or hides them.
- Statistics: five looks for the page — tiles, a week grid of the hours you watch, a magazine, a 24-hour clock and a highscore board. The "View" button next to the periods or the menu key switches between them right on the page; Settings → Statistics → View sets it too.
- Clips: the menu key switches the layout right on the page; a small note in the corner names the current one.

### Changed
- ShrugTV is now licensed under the GNU GPL v3.0 instead of the Apache License 2.0.
- With "Press BACK twice to close the stream", the hint after the first press says what the second does: "Press BACK once more to leave the stream".
- Leaving a page now stops its loading requests, and the followed channels are kept for a minute, so home, clips and Home Assistant no longer load them separately.
- Settings the TV keeps to itself without a PIN are greyed out on the phone page with a hint to pair with the PIN.
- Settings → Home → Rows holds every home screen row in one place: show or hide it and move it up, in the order of the home screen.
- Every settings page has a short guide and long guides were shortened; the two time settings are named "Duration format" and "Clock format".
- The two chat bot filters are one switch "Hide bots"; an enabled "predict bots" switch carries over.
- Build type, build variant and user ID moved from About to the developer page.
- Without the companion PIN, the settings that decide who reaches or steers the TV (companion, installing updates, MQTT broker, account and commands) can only be changed on the TV; a phone paired with the PIN may still change them from the browser.
- The Twitch sign-in (access and refresh token) and the MQTT password are stored encrypted with a key in the Android Keystore (AES-256-GCM) instead of as plain text; an existing sign-in is kept on the update, and a lost key only asks for a new sign-in.
- TV: the clips screen, the statistics and the other texts added since the last translation are now in all twelve languages instead of English (161 texts and 16 lists per language).
- Phone page: brings its own font (Inter, as on the TV) and a large and a maskable icon for the home screen; the TV no longer reads its settings file anew for every request of the settings tab; fast typing in the search sends at most two searches at a time.
- Phone page loads much faster after the first visit: files are compressed and kept by the browser, and only the language of the browser is loaded.
- Phone page: the log tab needs a paired phone even without PIN; remembered devices expire after 90 days without use and are renewed on every visit.
- The menu key works the same on every page: on a card, channel, category or clip it opens its menu, like holding OK; where the focus has no menu it switches the page view (Statistics, Clips). Each press acts once, when the key is released.
- One word for each thing in English and German: "recording" instead of VOD, "home screen" and "splash screen" kept apart, "View" for the switchable look of Statistics and Clips, "Text size" everywhere. Settings texts that were jokes now say what the switch does.
- Buttons: the chosen tab, period or filter has an accent frame, the focused button is the light one, so the two never look alike. Buttons follow the focus style of Settings → Appearance like cards do.
- Search, category, channel, home and statistics tell a failed request apart from an empty list: "Could not load this" with a "Try again" button instead of "No results". Every page shows loading, empty and error the same way.

### Fixed
- Statistics: a channel no longer appears twice in the ranking when some of its sessions were saved before the channel was looked up.
- Phone companion: when Twitch does not answer, lists and search show the error with "Try again" instead of "nothing found".
- A stream started by a link (Home Assistant, a launcher row) returns to the home screen on Back instead of closing the app.
- Opening the app after Android had closed it in the background no longer shows a black stream: shortly after leaving the stream starts again, later the app opens on the home screen.
- Leaving a stream with Back answers at once (sound off, "Leaving the stream …"), and a Back pressed right after it no longer leaves the app.
- The phone page shows loading, empty and error states with "Try again" on the Find and Version tabs, ignores answers of an older list or search, always returns to pairing when the TV no longer knows the phone, no longer adds log lines twice, and checks for updates only while the tab is visible.
- A clip opened by link shows the button "Live" again when its channel is live.
- Recordings: when the length of a video was not known yet, a nonsense length could be saved.
- Chat: animated emotes are decoded at normal thread priority instead of background priority, so they keep their speed while a stream plays.
- Chat: an animated emote is decoded once for all messages that show it, not once per message: the same emote many times in the chat no longer slows every emote down.
- Chat: 7TV and FFZ emotes load in the size the chat shows (2x, 64 px, instead of 4x, 128 px): a quarter of the pixels to decode, keep and draw per frame; scaled emote images are shared by all messages instead of copied per message.
- Phone page: the connection is closed and reopened when the TV has not been heard from for 50 s (the server now sends a sign of life every 20 s); the small pin buttons in the settings are no longer faint; saved/error notes are announced by screen readers.
- The update only downloads the APK over https; the settings endpoint answers a body without length with 411; the phone page is told only about changes of its own settings.
- Phone page: the chat no longer shows messages twice after switching tabs or after a lost connection; typed messages stay in the field while the TV is not reachable, and the page reconnects at once when the network is back.
- Phone page: tab buttons have names for screen readers also on narrow screens, the chat is a log region, the tabs work with the arrow keys.
- A web page on another site can no longer open the phone page's connection to steer the TV.
- Clips: when the list of followed channels cannot be loaded, the page shows the error with "Try again" instead of "No clips found"; signed out it still says "No clips found".
- The greeting on the start screen shows your name with its capitals right away instead of first in lower case; the name is kept from the last start.
- Companion: a web page opened in the home network can no longer reach the companion through DNS rebinding; only requests addressed to the TV's own IP address or localhost are served.
- Companion: a connection that sends nothing is closed after a minute instead of holding a thread for ever; the phone page stays connected.
- Delayed chat messages no longer arrive after the player was closed.
- The channel switcher in the player shows the chosen list with a frame, so it no longer looks like the focused one.
- Clips: in the stage view, the note of an empty list no longer lies over "Top 10".
- Clips: holding the menu key no longer races through the layouts; each press switches once.

### Removed
- The developer switches "Use TOKEN for streams" and "Show errors and exceptions", which had no effect.

## 2.4.0 – 2026-11-19

### New
- Clips page: a category button next to the periods (all categories, then the categories of the clips, most clips first) filters the list; the queue then plays only that category.
- Clips page: the list now really keeps going. When everything of the first request is on screen, the next page of clips of every channel is fetched (up to 2000 clips); with "Play the next clip" the queue goes on through all of it.
- Home screen: an optional second row "Surprise hits from your channels" (Settings → Home → Clips → "Show Surprise hits on the home screen", off by default): the clips of the last 7 days that stand out most from what is usual in their own channel. Its place is set under "Row order" like the others.
- Clips in the player: a button "Live" goes to the live stream of the clip's channel; it is dimmed while the channel is offline.
- Home Assistant: `watch` with `{"clip": "<slug>"}` plays one clip, also when no player is open, for example the clip of the day from an automation.

### Changed
- Clips page: four layouts to choose from under Settings → Home screen → Clips → Layout. **Grid with menus** (default): one row of menus for time frame, channels, category and sort over a grid of four larger cards with rank, views, a readable length (0:18) and a two-line title. **Stage with rows**: the focused clip fills the background with its title and numbers, the time frame as tabs, rows for the top ten with large numbers, new since yesterday, short ones and the biggest categories. **Charts**: a ranked list beside the focused clip, large, with views, length and place. **Clip TV**: the categories as channels on the left, the first clip of the channel across the screen and what comes next at the bottom; OK plays the channel through.

### Fixed
- Settings: entering a page with a preview (Appearance, Player, Chat, Home screen) is one smooth movement now: list and preview slide left together while the group rail narrows, instead of the list re-wrapping its text as it moved and the preview popping up at once.
- Settings: arrow right from the groups and opening a sub-page now start at the first entry instead of the row at the same height, and Back returns to the entry that opened the sub-page instead of the top of the list.
- Settings: a long current value (such as "Only under the focused button (more buttons fit)" under Player) took the whole row, and the title beside it stood letter by letter; the value is now cut with "…" at a third of the row, the full text is in the choice dialog.

## 2.3.0 – 2026-11-14

### New
- Statistics page: a new line "Clips watched" shows how many clips you watched in the period, how long they played, the average length and the channels most of them came from. A clip counts after 5 seconds (streams and recordings after a minute) and is no longer part of "Recordings" in the live-against-recordings ring, but still of the total time. It can be switched off under Settings → Home screen → Statistics page.
- Clips: the list grows while you scroll. The page shows one load of clips (Settings → Home screen → Clips → "Clips per load", 20 by default) and adds the next load two rows before the end. The queue is not limited to what is shown: with "Play the next clip" the clips go on through the whole pool (up to 500 clips, the best 50 of each channel).
- Clips: the title of a playing clip stays in the top left corner, with the channel and the place in the queue ("2 / 12"); it can be switched off under Settings → Home screen → Clips → "Show the clip title". With the bar open, the channel info shows the title.
- Clips in a queue: the buttons "Previous clip" and "Next clip" in the player bar go to the neighbours in the list the clip was opened from; at the ends the button is dimmed.
- Clips: a button "Recording" in the player bar jumps to the place in the recording the clip was cut from, as soon as that recording is available. If there is none (they expire after a few weeks), the button is dimmed and does nothing.
- Clips play one after the other: when a clip ends, the next one of the list it was opened from starts (the Clips page as chosen there, or the home row). Clips you opened before are left out of that queue; both can be changed under Settings → Home screen → Clips ("Play the next clip", "Clips I already opened in the queue"). Every clip that starts counts as opened.
- Link `shrugtv://clip/<slug>?queue=<slug>,<slug>` plays the clips one after the other, for Home Assistant or other apps.
- Clips show the chat that was running when they were recorded. A clip has no chat of its own, so the app looks up the recording it was cut from and plays its chat from the second where the clip starts. When that recording no longer exists (they expire after a few weeks) or chat of recordings is off, the chat panel stays away instead of showing an empty window.
- Clips: the sort option "Relative to channel size" compares each clip with the other clips of its own channel (views divided by what is usual there), so a very good clip of a small channel can rank next to one of a big channel. It is also a button on the Clips page.
- Clips: two more settings and a sort option. "Hide clips I already opened" leaves out clips you opened before (the last 500 are remembered), "Language of the clips" can keep only clips in your stream language, and "Shortest first" sorts by length.
- Link `shrugtv://clip/<slug>` plays one Twitch clip, like `shrugtv://stream/<login>` and `shrugtv://vod/<id>` do for streams and videos.
- Clips: a new page in the side bar (play symbol) and a home row show the best clips of the channels you follow in one list, so you do not have to open every channel. Settings → Home screen → Clips: whose clips (everyone you follow or favorites only), how far back (24 hours, 7 days, 30 days, year, all time), sort by views or newest, how many, and whether the row shows on the home screen; the page itself has the same choices.
- Statistics page: Settings → Home → "Statistics page" shapes it: the period it opens with, the time format (1 h 35 min, 1:35 h or 1.6 h), the comparison with the period before, each card on or off (figures, activity chart, time of day, top channels, top categories, live and recordings), the length of the top lists (3, 5, 8 or 10) and whether they rank by time or by number of sessions, the activity chart as bars or a line, marking the busiest day, the time of day as 24 hours or four parts of the day, live and recordings as a ring or a bar, and how long the data is kept (1, 2 or 5 years). Everything is also in the browser settings.
- Phone page and settings: the new texts (touchpad, chat tools, version tab, channel lists, stars, skin and followed-channels switches) are translated into all twelve languages.
- Phone page: a star on every channel card and next to the channel that is playing marks it as a favorite (the same favorites as the star on the TV). Favorites come first in the list of followed channels that Home Assistant gets, so the dashboard shows them first.
- Browser companion: the home screen rows can be sorted from the browser (settings page "Home screen rows": the list is in the current order, "Move up" per row, reset), and "Clear caches" runs from the browser too.
- Home Assistant (MQTT), followed channels: channels marked with the star in ShrugTV carry `favorite: true` in the list, so a dashboard can show them first.
- Home Assistant (MQTT): new switch "Followed channels" (off by default) publishes who you follow and who is live (game, title, viewers) as the topic `followed` with two sensors, "Live channels" and "Followed channels", whose attribute `channels` holds the list for a dynamic dashboard; updated every minute and when someone goes live.
- Phone page: it wears the colours of the skin picked on the TV (also your own skins and single colours) and follows live when you change them; the installed icon takes the colours too. Settings → Connections → Phone & browser → "TV skin on the phone page" (on by default) turns it off; the version tab explains how to put the page on the home screen of an iPhone or Android phone.
- Phone page: new "Version" tab with the installed version, edition, TV and Android, the result of the last update check, "Check now" and, in the public edition with "Install updates in the app" on, "Install update": the TV downloads the release, checks its SHA-256 and asks for the last OK on the TV; a red dot on the tab shows a newer version.
- Phone page, remote: touchpad (swipe = arrow keys, a long swipe keeps scrolling, tap = OK, two fingers = Back, with a small vibration), switchable to the key pad; new buttons "Replay" (live streams) and "Audio only"; "Recently watched" channels as quick picks.
- Phone page, chat: Twitch badges in front of the names, tap a message to answer it with @name, an emote panel with the emotes seen in the chat, and quick phrases you can add and remove (hold). Home Assistant (MQTT) can send `replay` and `audio_only` as well, and the state carries `audio_only`.
- Update check: with the check on, the home screen shows a small notice "Version X is available" at the start for a few seconds (where to find the card: Settings → App → Check now); it only informs and takes no focus.

### Changed
- Clips in a queue: a few seconds after a clip starts, the next one is loaded in the background (address looked up, the first seconds buffered), so the change to it is almost instant instead of a pause of about two seconds.
- Clips you opened before are now dimmed by default (before: shown normally). The choice is under Settings → Home screen → Clips.
- Clips: "Hide clips I already opened" is now the choice "Clips I already opened": show normally, dim them, or hide them. A switch that was on becomes "hide".
- Statistics page redone: cards on one screen without scrolling (time watched with the comparison, days, sessions, longest session), a new activity chart with one bar per day (per month for the last year) that marks the busiest day, the time of day, the top 5 channels and categories with ranks, and a ring for live against recordings. The periods sit at the top right.
- German texts: where people say the English word, the app does too: Button instead of Knopf, Replay instead of Nochmal, Cache, Backup, Badges, Dropped Frames, Nur Audio, Watchtime; "Zu Live springen" instead of "Zum Live-Rand", "Frühere Streams" instead of "Vergangene Übertragungen", "Zeitleiste", "Bildschirmschoner"; the group "Handy-Begleiter" is now "Handy & Browser" like its menu entry; the phone page says Edition, Schnellnachricht and "Chatten vom Handy".

### Fixed
- Watch time of a clip that was taken over from the preload (the next clip of a queue) was not counted, and a clip opened by link had no channel in the statistics.
- Clips page: after pressing "Favorites" (or any other choice) the old list stayed on screen while the new one loaded, and the focus jumped into the list, so it looked as if nothing worked. Now the list is replaced by "Loading clips …" at once, the focus stays on the button so the next one can be pressed, and the last four lists are kept, so switching back is instant. Loading is lighter too: 30 clips are asked per channel instead of 50.
- Chat of a recording: a page of messages asked for before a jump (seek, clip to its recording) and arriving after it no longer sends the following requests to the old position.
- Clips did not play: the player only knew HLS streams, but a clip is an MP4 file, so opening one gave a black screen. The player now picks the right kind by the address.
- Phone page: the favourite star in the channel lists sits at the right edge of the card instead of on top of the avatar.
- Home Assistant (MQTT), followed channels: the list no longer carries pictures, only names, and for those live game, title and viewers, so all channels fit under the limit of Home Assistant.
- Home Assistant (MQTT), followed channels: with many followed channels the live titles are kept; the pictures of the followed list are left out first.
- Phone page, chat: the Twitch badges now arrive in front of the names (the chat message did not carry which badges it had).
- Player: no crash on opening when the setting "answered message length" in the chat settings was changed with the slider on the TV (the number was read as text).
- Phone page: after an update of ShrugTV the browser no longer shows the old page; the page asks for its script and style with a checksum of the file.
- Repository: the GitHub build check uses current versions of its actions (checkout, setup-java, upload-artifact v5), which removes the Node 20 deprecation warnings.

## 2.2.0 – 2026-11-13

### New
- Public edition: Settings → App → "Install updates in the app" (off by default) adds an "Install now" button to the update card: the app downloads the release, checks its SHA-256 and opens the system installer, where you confirm; it needs the system permission "Install unknown apps" for ShrugTV once. The private edition never has this permission.
- Update check: "Check now" shows a card for a newer version with the changes, a QR code to the release page and the buttons "Later" and "Skip this version"; the update texts are translated into all languages.

## 2.1.2 – 2026-11-13

### Fixed
- Repository: the GitHub build check (Android CI) runs again; `gradlew` was not marked executable, so every run failed at the first step.

## 2.1.1 – 2026-11-13

### New
- Install with the Downloader app on Fire TV, Android TV and Google TV: enter the code 4727814, no PC needed; the README has the steps and the code always loads the newest release.

## 2.1.0 – 2026-11-13

### New
- Public edition: Settings → About → "Check for updates" (off by default) asks the ShrugTV release page once a day whether a newer version exists and shows a notice with the link; "Check now" asks right away. Nothing is downloaded or installed.

## 2.0.0 – 2026-11-13
