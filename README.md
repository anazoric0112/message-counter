# Poruke Counter for Android

Standalone Kotlin Android app for the WhatsApp analytics in the parent directory. The original Python scripts are unchanged. Android 8.0 (API 26) or newer is required.

## Build and run

Open this directory as a project in Android Studio. Select JDK 17 for Gradle, install Android SDK Platform 34, sync, then run the `app` configuration on a device or emulator. Android Studio can create `local.properties` for the local SDK installation; that file is ignored by version control.

From PowerShell in this directory, with JDK 17 in `JAVA_HOME`:

```powershell
.\gradlew.bat :core:test :app:lintDebug :app:assembleDebug
```

The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`. For a connected device with USB debugging enabled:

```powershell
.\gradlew.bat :app:installDebug
```

The first build requires internet access to download Gradle and dependencies. The app itself has no internet permission. This initial build targets API 34 for the installed SDK; a Play Store release will require the then-current target SDK and a release signing configuration.

## Import and analyze

1. Export a WhatsApp chat **without media**, and select its UTF-8 `.txt` file through **Add WhatsApp exports**. ZIP archives and media attachments are not imported.
2. Add one or several exports. Files accumulate in selection order, without deduplicating overlapping messages. Selecting the same URI again does not add it twice. Remove an unwanted file with its trash button.
3. Enter participant names, one per line. Defaults are `Ana`, `Zara`, start month `0.0`, end month `12.99`. Previously saved values take precedence.
4. Choose **Messages** or **Words**, select counting rules, and press **Analyze**. Analysis runs off the UI thread and can be cancelled.
5. Open **Graphs** or **Reports**. Report filters are under **Report settings**. Change local options and press **Update graph** or **Update reports** to apply them. Exports use the last successfully rendered output.

File permissions and form settings are retained across app restarts. Results survive screen rotation; after a fresh process launch, press Analyze again. Files remain in their original location and must still be accessible. Settings and pending exports survive normal activity recreation, but an export interrupted by process termination must be started again.

The Import start value `0.0` is an open starting boundary for modern chat dates, not a calendar month zero. The default `0.0` through `12.99` covers 2000-2099 and displays the span from the first to the last included chat month, retaining empty months between them instead of showing a century of empty results. With an explicit ending month, `0.0` starts at the first included chat month and keeps that ending month. Explicit start/end ranges keep their configured empty months. These defaults do not change the Python scripts or your saved custom range.

## Feature mapping

| Python entry point | Android output and controls |
| --- | --- |
| `read_texts` | Message counting; per-file and combined participant totals |
| `read_texts_count_words` | Word counting, including the original media exclusion |
| Participant printing in `main.py` | Participant counts and percentages; choose all files or one export |
| `plot_texts(starting_date)` | Overview: daily totals, monthly totals, cumulative totals and the final month in the selected graph interval; inclusive start/end dates on all four panels |
| `plot_texts_months()` | Monthly totals |
| `plot_by_hours(names)` | Per-person hourly graph, hours 0-23 |
| `plot_by_hours_softer(names)` | Per-person ten-minute buckets |
| `plot_month_per_person(names)` | Per-person monthly graph |
| `plot_weekday_per_person(names)` | Per-person weekdays, Monday=0 through Sunday=6 |
| `plot_day_per_person(names, starting_date, ending_date)` | Per-person daily graph; inclusive boundaries default to `0.0.0` and `31.12.99`; tick spacing defaults to 15 |
| `plot_per_person(...)` | Participant checkboxes; title; X/Y grid; legend; figure width/height; explicit comma-separated tick labels; tick spacing |
| `print_days(starting_date)` | Month-paged calendar with daily counts, selected-day detail and busiest day; starting date; CSV retains the nonzero daily rows |
| `print_months(starting_month)` | Month rows with all-year or paged single-year columns, year totals and a highlighted busiest month; starting month defaults to `0.0` |
| `last_m_print(ab, fb, tr, trackers)` | Latest-month calendar, totals, participant ratio and pair-minus-range difference; choose numerator/denominator and totals source; the obsolete export-limit warning is omitted |

The generic graph's X/Y arrays come from the selected tracker; they are not manual numeric inputs. A name subset affects per-person graphs, not combined-total graphs. Starting and ending dates are shared across all graph modes; titles, participant selections and other chart settings are still stored separately for each mode.

Participant names beside the checkboxes match their on-screen lines and points. Each participant keeps the same color when other participants are deselected. Legends default to off and can be enabled under **Chart settings**; previously saved legend choices are retained. PNGs use the existing higher-contrast palette for white backgrounds, with stable participant colors and matching optional legends.

Every graph has **Starting date** and **Ending date** fields. Press **Update graph** to apply an inclusive interval: both boundary days are counted. Switching graph modes carries the dates currently entered into the next graph, even before pressing Update graph. The shared dates survive rotation and app restarts. On upgrading from per-graph dates, the previously selected graph's saved interval initializes the shared range. Use dates such as `1.7.26` and `31.7.26`, or their four-digit-year equivalents. The defaults are `0.0.0` (no lower cutoff) and `31.12.99` (31 December 2099). PNG and graph CSV exports use the last applied interval.

Date filtering is an app enhancement beyond the original Python graph arguments. Monthly graphs sum only the included days, so a partial-month interval does not include the rest of that month. Hourly and ten-minute graphs sum date-specific time buckets; weekday graphs sum the selected days using the chosen counting rules. All four Overview panels use the same interval, cumulative totals start within it, and its final panel shows the last month intersecting that interval. Graph filters never change the imported totals or Reports. Daily, monthly and weekday data remain limited to the Import month range; in Python-compatible mode, hourly data still includes imported dates outside that range when the graph interval includes them.

Graphs use AndroidPlot. The four overview panels retain their existing layout, stack on the phone and export as a 2x2 PNG. Other graphs open fitted to the available screen width so the complete selected data range is visible without horizontal scrolling. **Expand graph** below the chart switches to the configured wide view with horizontal scrolling; **Fit to screen** returns to the fitted view and resets its scroll position. The toggle changes only the on-screen width, not the data, filters, chart height or exports. Other graphs export individually.

Figure dimensions default to 15x5 inches (overview 8x5), with PNG export at 100 DPI; supported integer dimensions are 4-30 wide and 3-20 high. The configured width also sets the expanded graph width. Monthly axes, graph CSV month labels and the Overview's final-month title use full names and years, such as `September 2026`. Daily axes and date inputs retain their numeric formats. Label spacing adapts to available width when no custom ticks or spacing are selected. The fitted view also thins labels in multiples of the configured tick spacing when necessary to avoid overlap, without dropping data points; expanding restores the original spacing. Explicit tick labels must match the graph's labels; numeric month selections such as `9.26` still work with the full month names. As in Python, tick spacing greater than one takes precedence over explicit tick labels. The two overview daily axes hide labels, matching the original plot.

Y-axis scales start at zero and use whole-number steps chosen from 1, 2 or 5 times a power of ten, with a minimum step of 1. Each chart adapts to the highest value among its displayed series after date and participant filtering, rounds the upper limit up with space above the data, and groups thousands without decimal places. For example, a peak near 9,700 uses ticks at 0, 2,000, 4,000 and so on up to 12,000. The scale remains linear and is shared by screen and PNG rendering; counts and CSV values are unchanged.

## Reports and appearance

The app uses a charcoal, purple and mint theme with fine grid lines, outlined controls and restrained heading glow. JetBrains Mono 2.304 is bundled for regular and bold monospace text. It provides a similar coding-font feel to Consolas without redistributing the proprietary Windows font. Its SIL Open Font License is included in `app/src/main/assets/licenses/JetBrainsMono-OFL.txt`. On-screen graphs follow the dark theme; PNG exports retain a white background.

- **Participant totals**, **Monthly counts**, **Daily counts**, **Latest month**, and **Summary** each have a tappable heading with an expand/collapse arrow. Sections start expanded and remember their states independently across report updates, rotation and app restarts. Collapsing hides only the section's content: selected years/days, counts and complete CSV exports are preserved. Headings expose their expanded state and expand/collapse actions to accessibility services.
- Report month labels read `September 2026`, and numeric daily dates read `23.9.2023.`. Two-digit years are expanded for display; full years are not prefixed again. The same readable dates are used in report CSVs. Report filter inputs retain the original numeric formats.
- **Monthly counts** has **All years** and **One year** modes. All years shows January-December rows with a column per year: month names stay fixed while year columns scroll horizontally on phones, and columns expand on larger screens. One year shows a single full-width count column with previous/next year arrows and a year picker. The mode and selected year are remembered. A dash means that month is outside the selected range; zero means an included month with no activity. Totals reflect the filtered months, and the busiest-month highlight covers the displayed year or all displayed years, depending on the mode.
- **Daily counts** shows one month at a time, with previous/next arrows and a month picker. Each day displays its exact count and activity shading. Tap a day to see its complete date and count below the calendar. The busiest day is separated into its own highlighted band. The selected month/day is remembered.
- **Latest month** uses the same calendar, daily counts, activity shading and selected-day/busiest-day highlights. It shows the full month selected by the existing counting rules, independently of the daily/monthly report filters. Its selected day is remembered separately from Daily counts.
- Tap a month count in the year table to open that month in the daily calendar, when it is included by the daily filter. This automatically expands **Daily counts** if it was collapsed. Empty months remain available. Dates before the daily starting-date filter are disabled; zero-count dates within the filter remain selectable.
- Calendar placement uses real modern month lengths and Monday-first weekdays, including leap days. This is a presentation choice in both modes, not a change to Python-compatible counters or weekday graphs. Nonexistent zero-count compatibility slots such as February 31 are not shown as calendar dates.
- Participant totals and other reports use aligned, striped rows, colored headers and separated totals. The Summary report is grouped into totals and import diagnostics. The old export-count/threshold/status section and its settings have been removed. Long non-calendar tables retain 100-row pagination.

The save icon beside **Report settings** still exports all report rows, not just the visible year, month or page, with full integer counts. CSV quoting uses Apache Commons CSV; formula-like text is prefixed with an apostrophe for spreadsheet safety. PNG and CSV exports use Android's destination picker, not a fixed `out` directory.

## Counting compatibility

**Python-compatible is the default.** Its tested reference is `core/src/test/resources/python-compatibility.json`. It intentionally retains these behaviors:

- Header format: `1.2.24., 08:09 - Alex: hello`. The original dot-separated two-digit-year format is required. Leading zeroes retain the original string-matching behavior in date trackers; for normal results, use unpadded dates like the Python input format.
- Every configured month has 31 internal slots, including non-calendar dates with zero counts. The Reports calendar displays only real dates.
- Weekdays use the literal year (for example year 24, not 2024).
- Sender matching is case-sensitive, uses prefixes, and the last matching configured name wins. An unknown sender inherits the previous recognized sender, even across files.
- Non-header continuation lines are ignored in both counting modes, just as `NameTracker.track` returns an empty name for them.
- Participant totals and hourly buckets include messages outside the configured month range. Day, month and weekday trackers do not.
- For message totals, the resolved participant's name must also occur in the line. Word counts preserve the original colon-slicing and unknown-sender behavior. Lines containing `<Media omitted>` are skipped in word mode.
- Ten-minute labels use the original bucket notation `08:00` through `08:05`, corresponding to actual 08:00 through 08:50.
- The overview's final panel uses the last configured month intersecting its graph interval, even when empty. The latest-month report retains the separate original reverse-search behavior, which does not inspect month index zero when multiple months are configured.

To reproduce the current two-file `main.py` workflow, import the primary export first and the extra export second, then select the first file as the report's participant-total source. Graphs still aggregate both files.

**Calendar-correct** is an explicit alternative, not parity mode:

- Real month lengths, leap days and weekdays; two-digit years map to 2000-2099.
- Exact, case-sensitive sender names; unknown senders are skipped, not inherited.
- Multiline message bodies count as one message; continuation words are included in word mode.
- Dot-separated two- or four-digit years and padded dates are accepted, with optional dot before the comma. Other locales, slash dates, AM/PM times and iOS bracketed headers are not supported yet.
- All counters, including totals and hourly buckets, use the configured date range.
- Actual ten-minute labels such as `08:50`, and latest-active-month selection including index zero.

Use one year convention consistently in Import configuration and report filters. Graph date filters accept two- or four-digit years independently of that convention; this comparison does not change Python-compatible weekday calculations. For dates after 2099, set the graph's ending year explicitly.

Safety fixes common to the app: reversed/oversized ranges and invalid form inputs are rejected; malformed dates/times are reported rather than crashing; empty totals do not divide by zero; the daily graph's default starting date works instead of triggering the Python uninitialized-variable error. These defensive cases are not intended to reproduce Python exceptions. The unused `check_msnikmm` debug helper is not an analytics output and is not exposed.

## Tests and structure

- `core/`: Pure Kotlin parsing, counters, chart datasets, report calculations and regression tests. Tests cover open-start Import defaults, stable participant colors for subset/reordered selections, inclusive filtering for every graph, partial months, dated time buckets, both counting modes, full-year dates, readable month labels, empty intervals and unchanged reports. No Android dependency.
- `app/`: Native Android views, retained model, document picker, AndroidPlot rendering and exports.
- `app/src/androidTest/`: Emulator smoke tests for import/analysis with default and saved month ranges, all seven graph modes sharing a saved date range, migration from per-graph dates, participant colors matching curves and optional legends, legends off by default with saved opt-in, activity recreation and relaunch, nonblank PNGs, full-month axis-label spacing and numeric tick compatibility, independently collapsible report sections with saved state and accessible actions, year navigation and saved modes, independent daily/latest-month calendars, removal of export-warning controls, and report text/count visibility at 320dp and 800dp widths.

```powershell
.\gradlew.bat :core:test
.\gradlew.bat :app:connectedDebugAndroidTest
```

Instrumentation tests use synthetic chats, not your personal exports, and restore saved app preferences after each test. Use a dedicated emulator: an interrupted test or Gradle's connected-test uninstall can still remove test-device data. For an already installed app and test APK, direct `adb shell am instrument -w -r com.porukecounter.app.test/androidx.test.runner.AndroidJUnitRunner` avoids Gradle's post-test uninstall. The system document-provider picker and destination-provider write flow still warrant a manual check on your own phone.

All analytics run locally. The app requests no broad storage permission and no network permission. It stores only settings and selected file references, not copies of imported chat content. Backup and device-transfer rules exclude app data. Exported files are intentionally written to the location you choose.