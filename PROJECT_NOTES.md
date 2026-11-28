# Project Notes, Learned Mistakes & Rules

## Workspace & Architecture
- **Module Name:** `ig-emoji-score(yathin)` (`com.example.module`)
- **Framework:** Vector / LSPosed fork supporting `libxposed` API `101` (`libxposed-api` submodule)
- **Target App:** Instagram (`com.instagram.android`), tested on `v451.0.0.20.80`
- **Build & Distribution:** Built on GitHub Actions (`.github/workflows/build.yml`) and published to GitHub Releases.

### Emoji Pong UI Structure (`ModalActivity`)
- Activity: `com.instagram.modal.ModalActivity`
- Arena: `androidx.constraintlayout.widget.ConstraintLayout` containing:
  - High-score container (`"HI ..."`) on the top-right (tapping opens the Leaderboard bottom sheet)
  - Score `IgTextView` (height `>= 220`, numeric text)
  - Ball `IgTextView` (`<= 250x250`, emoji text)
  - Paddle `com.instagram.common.ui.base.IgView` (`120..600` x `40..250`)

## Learned Mistakes & Fixes
1. **Use exact `libxposed-api` 101 signatures:**
   - `libxposed-api` 101 does **not** have `@XposedHooker` / `@BeforeInvocation` / `@AfterInvocation` annotations, `XposedModule(base, param)` constructor, or `log(String)`.
   - Use `hook(executable).setPriority(...).setExceptionMode(...).intercept(chain -> ...)` and `onPackageReady(PackageReadyParam param)` with `log(int priority, String tag, String msg)`.
2. **Keep AGP and Gradle wrapper aligned with `libxposed-api`:**
   - `libxposed-api` HEAD uses AGP `9.2.1`, which requires Gradle `9.4.1` (`gradle/wrapper/gradle-wrapper.properties`) and matching `com.android.application` version `9.2.1` in `build.gradle.kts`.
   - Remove unused `:libxposed-compat` from `settings.gradle.kts`.
3. **Avoid per-frame allocations & full-tree scans in `Choreographer`:**
   - Never allocate `new GradientDrawable()`, call `setBackground()`, or call `setX()`/`setY()` every frame—it triggers constant layout/redraw passes and causes severe game lag and paddle jitter.
   - Throttle `isLeaderboardOpen()` `DecorView` scans (e.g., every `150ms`) instead of running on every vsync frame.
4. **Smooth paddle tracking & touch handling:**
   - Use sub-pixel `View.getX()` / `View.getY()` coordinates relative to the arena rather than rounded `int[] getLocationInWindow()` deltas.
   - Apply a small deadband (`1.5px`) and gated velocity lead (`|dx| > 4f ? dx * 0.55f : 0f`), and only re-anchor `ACTION_DOWN` if genuinely stuck (`stuckFrames >= 25`).
   - Allow top-bar touches (`ev.getRawY() < arenaTop + 280f`) so the Leaderboard and on-screen `Auto: ON / OFF` toggle work mid-game, and wait `300ms` after the Leaderboard closes before resuming `ACTION_DOWN`.
5. **Preserve normal Volume Up & multi-module compatibility:**
   - Only trigger the in-game menu when `ModalActivity` is active and `isArenaValid()` is true, ignore key repeats, and always return `chain.proceed()` so system volume works normally everywhere.
   - Use `XposedInterface.PRIORITY_LOWEST` and `XposedInterface.ExceptionMode.PROTECTIVE` on hooks, and stop the `Choreographer` loop outside `ModalActivity`.

## Rules
- Build APKs via GitHub Actions rather than local container builds.
- Note: GitHub OAuth credentials without the `workflow` scope cannot push modifications to `.github/workflows/*.yml`.
- Keep all source, docs, and releases clean and self-contained in the GitHub repository.
