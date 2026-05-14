package `in`.jphe.storyvox.data.coroutines

import javax.inject.Qualifier

/**
 * Distinguishes the singleton, app-lifetime [kotlinx.coroutines.CoroutineScope]
 * from any other `CoroutineScope` someone might bind in the Hilt graph.
 *
 * The bound scope uses [kotlinx.coroutines.SupervisorJob] +
 * [kotlinx.coroutines.Dispatchers.Default], so a thrown exception in one
 * child coroutine doesn't kill the scope itself. Suitable for fire-and-forget
 * hydrators on `@Singleton` repositories (e.g. [`in`.jphe.storyvox.data.repository.AuthRepositoryImpl]'s
 * init block) that need structured concurrency but aren't tied to any
 * viewmodel.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
