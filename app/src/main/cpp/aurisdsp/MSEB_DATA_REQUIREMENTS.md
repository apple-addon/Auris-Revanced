# MSEB recovery and validation boundary

The recovered control mapping and coefficient formulas have now been supplied
and incorporated. The source of truth transferred with the implementation is
`tools/aurisdsp/HIBY_MSEB_RECOVERED.md`.

## Incorporated data

- Thirteen native sections in their original cascade order: four simultaneous
  overall-temperature high shelves followed by nine dimension-specific filters.
- Exact frequency, Q, native filter type, and gain-scale constants from arm64
  `libhibyservice.so` initialization at `0x1820c0` and setters at `0x183bec` and
  `0x183d48..0x183d90`.
- Raw slider domain `-100..+100`. The JNI ABI carries normalized `-1..+1` and
  the isolated mapping multiplies by 100 exactly once. Range values 25/50/100
  remain metadata and do not rewrite or attenuate raw sliders.
- Standard RBJ peaking/low-shelf/high-shelf equations verified against
  coefficient routine `0x1815c0`, with `A = 10^(gainDb/40)`,
  `alpha = sin(omega)/(2Q)`, and normalization by `a0`. The shared `Biquad`
  implementation uses those equations and double-precision evaluation.

`buildMsebMapping` in `MsebMapping.cpp` remains the single isolated mapping
function. A different reference build or newly recovered behavior should be
applied there rather than spread through the engine.

## What the host expected-response tests establish

The tests independently duplicate the recovered table and check every section's
frequency, Q, type, enabled state, and raw-slider gain. Synthetic impulses check
all four temperature sections together at DC and Nyquist, both shelf asymptotes,
and every peaking section's center gain. Another test requires bit-identical
output when only range metadata changes. Normal and ASan/UBSan host runs exercise
these checks without Gradle.

These are analytic expected-value tests of the recovered constants and RBJ
implementation. They are not recordings from the proprietary library and must
not be presented as direct native PCM null tests.

## Evidence still needed for a bit-identical native-output claim

For an end-to-end bit-identity statement against a specific reference build,
retain its SHA-256/version/ABI and compare undithered Float32 input/output pairs
from that build with this engine. Include:

- neutral, each dimension at negative/positive nonzero values, all four
  temperature sections together, mixed dimensions, preamp, and all range values;
- impulses, fixed sines, and log sweeps at 44.1, 48, 88.2, 96, and 192 kHz;
- initial-state, reset, enable/disable, seek/track boundary, parameter-transition,
  and different block-size captures;
- mono, stereo, left-only, and right-only signals to expose channel coupling;
- explicit reference latency, state realization, coefficient-update cadence,
  denormal handling, saturation, and final Float32 rounding behavior.

The comparison must define bit equality or numerical tolerances and use some
captures withheld from coefficient fitting. This additional evidence concerns
runtime integration and state semantics; no further frequency/Q/gain-scale data
is missing from the supplied 13-section initialization table.
