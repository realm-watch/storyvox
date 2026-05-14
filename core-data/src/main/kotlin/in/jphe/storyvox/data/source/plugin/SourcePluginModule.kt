package `in`.jphe.storyvox.data.source.plugin

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Plugin-seam Phase 1 (#384) — declares the empty
 * `Set<SourcePluginDescriptor>` multibinding so Hilt can resolve
 * [SourcePluginRegistry] even when zero `@SourcePlugin`-annotated
 * classes exist on the classpath (instrumentation tests, the Wear
 * module, future builds where one source module isn't linked).
 *
 * Without `@Multibinds`, Hilt would error at graph-resolution time if
 * no `@IntoSet` contributor exists. With it, the set defaults to
 * empty and the KSP-generated `@Provides @IntoSet` factories add to
 * it. Same pattern Dagger has documented for empty-by-default
 * multibindings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SourcePluginModule {

    @Multibinds
    abstract fun sourcePluginDescriptors(): Set<SourcePluginDescriptor>
}
