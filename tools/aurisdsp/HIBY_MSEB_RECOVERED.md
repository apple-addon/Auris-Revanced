# HiBy MSEB recovery — libhibyservice.so (arm64)

Source: `/root/Apks/hibs_rebranded/lib/arm64-v8a/libhibyservice.so` (the extracted HiBy reference tree currently present in this environment; the earlier recovery pass referred to the same library as `hibs_final`).

## Exact native mapping

Thirteen biquads, 0x30 bytes per native parameter structure. Filter types:
`0 = low shelf`, `1 = peaking`, `2 = high shelf`.

| MSEB control | Native index | Frequency Hz | Q | Type | Gain dB per slider unit |
|---|---:|---:|---:|---:|---:|
| Overall temperature (four simultaneous sections) | 0 | 33 | 0.4424999952316284 | 2 | -0.06 |
| Overall temperature | 1 | 231 | 0.4424999952316284 | 2 | -0.06 |
| Overall temperature | 2 | 1617 | 0.4424999952316284 | 2 | -0.06 |
| Overall temperature | 3 | 11319 | 0.4424999952316284 | 2 | -0.06 |
| Bass extension (`bass0`) | 4 | 70 | 0.7289000153541565 | 0 | +0.25 |
| Bass texture (`bass1`) | 5 | 100 | 0.8571000099182129 | 1 | +0.20 |
| Note thickness (`thickness`) | 6 | 200 | 0.666700005531311 | 1 | +0.12 |
| Voice (`vocal`) | 7 | 650 | 0.4041000008583069 | 1 | +0.50 |
| Female overtones (`female`) | 8 | 3000 | 1.4140000343322754 | 1 | +0.10 |
| Sibilance LF (`male_vocal`) | 9 | 5800 | 0.9991000294685364 | 1 | +0.10 |
| Sibilance HF (`female_vocal`) | 10 | 9200 | 0.4041000008583069 | 1 | +0.10 |
| Impulse response (`instruments`) | 11 | 7500 | 0.7289000153541565 | 1 | +0.10 |
| Air (`air`) | 12 | 10000 | 0.8571000099182129 | 2 | +0.25 |

`gainDb = sliderValue * scale`. Slider values remain in `-100..100`; changing UI range does not change the stored raw values.

## Coefficients

`A = pow(10, gainDb / 40)`, `omega = 2*pi*frequency/sampleRate`, `alpha = sin(omega)/(2*Q)`.

Peaking:

```
b0 = 1 + alpha*A
b1 = -2*cos(omega)
b2 = 1 - alpha*A
a0 = 1 + alpha/A
a1 = -2*cos(omega)
a2 = 1 - alpha/A
```

Low/high shelves use the standard RBJ equations with `sqrt(A)`. All three normalize coefficients by `a0`. Native response evaluates in double; PCM conversion to Float32 happens after processing.

Evidence: initialization at `0x1820c0`; parameter setters at `0x183bec` (master temperature) and `0x183d48..0x183d90` (other dimensions); coefficient construction at `0x1815c0`; response evaluation at `0x181bf0`.
