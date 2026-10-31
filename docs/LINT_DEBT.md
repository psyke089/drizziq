# Initial Android lint debt

The initial `app/lint-baseline.xml` records findings present when the local verification gate was introduced. The gate treats warnings as errors outside this baseline. This is an explicit inventory of existing work, not a rule to regenerate the baseline after a failed check.

The initial inventory contained 19 findings across eight lint IDs. Removing redundant radar UI text resolved six `SetTextI18n` findings; those entries have been removed, leaving 13 findings across seven lint IDs. Replacing the obsolete `TemperatureTrace` removed its constructor finding, leaving 12 findings across those seven IDs. With `warningsAsErrors`, lint displays these existing warnings as errors during baseline creation.

The findings cover:

- **View drawing allocations:** existing custom glyphs/traces construct drawing objects in draw callbacks. Move reusable paths/paints out of those callbacks when working on rendering performance.
- **View constructors:** some Views are created exclusively from Kotlin and do not have XML-inflation constructors. Add constructors if introducing XML layouts or layout previews.
- **Touch accessibility:** the radar map needs its tap behavior routed through `performClick`; verify that drag/pinch gestures remain correct when fixing it.
- **Themed launcher icon:** the adaptive icon has no monochrome layer for Android themed icons. Add a monochrome version when working on themed-icon support.
- **Backup rules:** explicitly define modern data-extraction rules alongside the existing `allowBackup=false` policy when addressing Android backup behavior.
- **Available tool/library updates:** version-availability findings do not automatically authorize dependency upgrades. Review compatibility and data-decoding regressions before changing pinned versions.

The baseline retains individual IDs, messages and source locations for review. Do not globally disable these checks. Fix relevant findings with behavior tests where appropriate, then remove the resolved baseline entries. New problems must be fixed or discussed, not appended to the baseline merely to pass verification.
