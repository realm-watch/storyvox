package `in`.jphe.storyvox.feature.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Feature-module Hilt entry point.
 *
 * The feature module owns no concrete bindings on its own — its ViewModels depend on
 * [`in`.jphe.storyvox.feature.api] interfaces, and the bindings for those live in
 * core-data and core-playback (or, for tests, in a fake module). The `app` module
 * is responsible for ensuring the right `@Provides` / `@Binds` exist.
 *
 * Kept as a placeholder so Hilt's component graph picks up this module path
 * during code-gen and so future feature-only bindings (e.g., a navigation router)
 * have a home.
 */
@Module
@InstallIn(SingletonComponent::class)
object FeatureModule
