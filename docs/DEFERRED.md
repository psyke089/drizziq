# Deferred work

Requirements intentionally left open after the 2026-11-03 implementation wave.
Each item is reachable; none is silently dropped. Everything else from the
temporary implementation plan and the review was delivered and is recorded in
[DEVELOPMENT.md](DEVELOPMENT.md), [DATA.md](DATA.md) and the
[review resolution audit](reviews/2026-11-01T00-20-44-ds41flash.md).

## 1. Commit, release and screenshot publication

The whole change set is uncommitted and the version remains 1.4.1. `README.md`
and `docs/screenshot.png` were updated from the current debug build on the
OnePlus 5T using the public Munich preset; the phone's original selected place was
restored afterwards. Publishing a new tag/release and attaching the raw captures to
the Codeberg release notes requires maintainer action (version bump, commit, tag,
push), which was not taken unasked.

To close: bump `versionName`, run the full gate and affected phone checks, commit
in independently verifiable slices, push/tag, then attach the raw captures and
embed them in the release notes per [DEVELOPMENT.md](DEVELOPMENT.md).

The planned sunset work in [future/SUNSET.md](future/SUNSET.md) is intentionally
untouched.
