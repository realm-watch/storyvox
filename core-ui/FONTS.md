# Fonts — downloadable approach

Library Nocturne uses two type families:

- **EB Garamond** — body / chapter text. SIL OFL 1.1 licensed.
- **Inter** — UI labels and navigation. SIL OFL 1.1 licensed.

We do **not** bundle TTF files. Both are pulled at runtime via Compose
[downloadable Google Fonts](https://developer.android.com/jetpack/compose/text/fonts#downloadable-fonts):

```kotlin
val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)
```

Why downloadable:

1. APK stays small (storyvox is sideloaded; size matters).
2. License compliance is automatic — Google's font provider handles attribution.
3. Fonts are cached system-wide and shared across apps.

The cert array lives in `core-ui/src/main/res/values/font_certs.xml`. The two
`GoogleFont` declarations (EB Garamond, Inter) live in `Typography.kt`.

If a user is on a device without Play Services (rare, but possible — graphene,
calyx), Compose falls back to the platform serif/sans-serif. That fallback is
acceptable for v1; we can revisit by bundling a subset TTF later if needed.
