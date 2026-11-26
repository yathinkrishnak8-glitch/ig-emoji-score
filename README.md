# ig-emoji-score(yathin)

An Xposed / Vector (`libxposed` API 101) module for Instagram (`com.instagram.android`, tested on `v451.0.0.20.80`) that auto-plays the DM Emoji Pong mini-game up to your custom target score and automatically loses once reached.

## Features

- **Auto-Play + Auto-Lose:** Uses `Choreographer` vsync frame callbacks with horizontal velocity prediction to track the emoji ball smoothly at high speeds, then dodges the paddle once your target score is reached so the round ends naturally and saves your score.
- **On-Screen Auto-Play Toggle:** Adds an **Auto: ON / OFF** toggle button directly to the left of the Leaderboard (`HI`) button inside Emoji Pong.
- **Mid-Game Leaderboard Support:** You can open and close the Leaderboard mid-game; Auto-Play pauses while the Leaderboard is open and automatically resumes when closed.
- **In-Game Target Menu:** Press **Volume Up** inside Emoji Pong to set your custom target score (default `99`), toggle Auto-Play, or view/copy logs. Normal Volume Up behavior is preserved everywhere.
- **Touch Lock & Keep-Screen-On:** Ignores accidental touches in the playfield during active Auto-Play while keeping the top bar interactive, and keeps the screen awake while playing.

## Requirements

- Rooted Android device with **Vector** (or LSPosed fork supporting `libxposed` API 101)
- Instagram (`com.instagram.android`) v451.0.0.20.80

## Usage

1. Install the APK from [Releases](../../releases) and enable **ig-emoji-score(yathin)** in Vector/LSPosed with scope `com.instagram.android`.
2. Open any DM Emoji Pong game in Instagram.
3. Use the **Auto: ON / OFF** pill next to the Leaderboard button, or press **Volume Up** to set your target score.
