# Contributing

Bug reports, hub test results and pull requests are welcome.

- **Run the tests** before sending a change: `tests/setup-tools.sh` once (it
  downloads a pinned JDK and Groovy 2.4 into the git-ignored `.tools/`), then
  `tests/run.sh`. CI runs the same suite.
- **Hubitat runs Groovy 2.4** in a sandbox. Stay with plain loops and the
  classes the code already imports. `tests/CompileCheck.groovy` refuses the
  usual traps (post-2.4 methods, `System.`, non-ASCII).
- **Mapping lives in the app** as pure functions; drivers stay thin. A change to
  how a Hue resource maps to Hubitat events or commands needs a MappingTest or
  DriverFlowTest case, built on synthetic data in `tests/fixtures/`.
- **Follow Hue's guidance**: no polling, paced commands, only the properties that
  change, HTTPS to the bridge. docs/SPEC.md lists the rules and how each is met.
- **On a hub:** `tests/hub/hub-test.py` (tests/hub/README.md) checks a change
  against a real hub and bridge.
- **Releases**: `scripts/release.sh X.Y.Z "notes"` stamps the version in the app,
  every driver and the HPM manifest; `tests/PackageCheck.groovy` refuses a
  mismatch. Add user-visible changes to CHANGELOG.md.
- Never paste a bridge application key in an issue, a log or a test fixture.
