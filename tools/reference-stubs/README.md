# Reference JNI contract stubs

These Java sources are **reference material only**. They document the public JNI
surface of the proprietary HiBy and Poweramp engines that were studied while
reimplementing MSEB/PEQ in `app/src/main/cpp/aurisdsp/`.

They are intentionally outside every Gradle source set and are **not compiled or
packaged** with the app. No Auris ReVanced code references these classes.

The matching proprietary `.so` binaries are not committed; they were moved out of
`app/src/main/jniLibs/` so they no longer ship in APKs. See
`docs/reference_hygiene.md` for the audit.
