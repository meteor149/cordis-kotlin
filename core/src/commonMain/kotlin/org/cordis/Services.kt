package org.cordis

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.jvm.JvmInline

/**
 * A typed name for a capability exposed through [Context].
 *
 * Cordis configuration still uses stable string names, while Kotlin callers can
 * keep the expected value type at the call site instead of repeating casts.
 */
class ServiceKey<T>(val name: String) {
    init {
        require(name.isNotBlank()) { "service name must not be blank" }
    }

    override fun toString(): String = "ServiceKey($name)"
}

/** Explicit boundary for a service name originating in runtime configuration. */
@JvmInline
value class ServiceReference(val name: String) {
    init {
        require(name.isNotBlank()) { "service reference must not be blank" }
    }
}

/** A typed name for configuration inherited through [Context.intercept]. */
class InterceptKey<T>(val name: String) {
    init {
        require(name.isNotBlank()) { "intercept name must not be blank" }
    }

    override fun toString(): String = "InterceptKey($name)"
}

/** Typed configuration channel associated with a [Service] key. */
fun <C, S : Service<C>> ServiceKey<S>.configKey(): InterceptKey<C> = InterceptKey(name)

/** A typed computed Context property. This is the Kotlin replacement for a Proxy accessor. */
class ContextProperty<T>(
    val name: String,
    val get: Context.() -> T?,
    val set: (Context.(T?) -> Boolean)? = null,
) {
    init {
        require(name.isNotBlank()) { "property name must not be blank" }
    }
}

/**
 * Exposes a service member as a computed Context property without platform
 * reflection. The generated name follows Cordis' `service.member` convention.
 */
fun <S : Any, T> ServiceKey<S>.property(
    member: String,
    get: S.() -> T?,
    set: (S.(T?) -> Unit)? = null,
): ContextProperty<T> = ContextProperty(
    name = "$name.$member",
    get = {
        val service = this[this@property]
        service?.get()
    },
    set = set?.let { setter ->
        { value ->
            val service = this[this@property]
            if (service == null) {
                false
            } else {
                setter(service, value)
                true
            }
        }
    },
)

/** Executes a service operation with the calling Context made explicit. */
inline fun <S : Any, R> Context.use(key: ServiceKey<S>, block: S.(Context) -> R): R =
    require(key).block(this)

/** Suspending counterpart of [use]. */
suspend inline fun <S : Any, R> Context.useSuspending(
    key: ServiceKey<S>,
    crossinline block: suspend S.(Context) -> R,
): R = require(key).block(this)

internal class ServiceBinding(
    val key: ServiceKey<*>,
    initialRealm: Any,
    val fiber: Fiber<*>,
    initialValue: Any?,
    val check: ((Context) -> Boolean)? = null,
) {
    val name: String get() = key.name
    private val currentRealm = atomic(initialRealm)
    private val currentValue = atomic<Any?>(initialValue)

    var realm: Any
        get() = currentRealm.value
        set(value) {
            currentRealm.value = value
        }

    var value: Any?
        get() = currentValue.value
        set(value) {
            currentValue.value = value
        }
}

private sealed interface ContextDeclaration {
    class Service(val key: ServiceKey<*>) : ContextDeclaration
    class Computed(val property: ContextProperty<*>) : ContextDeclaration
}

/**
 * Root-owned service registry and resolver.
 *
 * This class contains the actual Cordis service semantics: realm isolation,
 * Fiber-local dependency snapshots, provider lifetime and dependent refresh.
 * Runtime names remain an internal storage detail used by loader metadata;
 * application code accesses capabilities through [ServiceKey].
 */
internal class ContextServices(private val root: Context) {
    private val lock = SynchronizedObject()
    private val bindings = IdentityMap<Any, ServiceBinding>()
    private val declarations = mutableMapOf<String, ContextDeclaration>()

    fun <T> get(ctx: Context, key: ServiceKey<T>, strict: Boolean = true): T? {
        val definition = synchronized(lock) { declarations[key.name] }
        if (definition is ContextDeclaration.Computed) {
            throw IllegalStateException("${key.name} is a computed Context property, not a service")
        }
        if (definition is ContextDeclaration.Service && definition.key !== key) {
            throw IllegalStateException("service \"${key.name}\" was requested with a different ServiceKey instance")
        }
        val binding = resolveBinding(ctx, key.name, strict) ?: return null
        check(binding.key === key) { "service \"${key.name}\" was provided with a different ServiceKey instance" }
        return erasedValue(binding.value)
    }

    fun <T> get(ctx: Context, property: ContextProperty<T>): T? {
        val definition = synchronized(lock) { declarations[property.name] }
        if (definition !is ContextDeclaration.Computed) return null
        check(definition.property === property) {
            "property \"${property.name}\" was requested with a different ContextProperty instance"
        }
        return property.get(ctx)
    }

    internal fun getDynamic(ctx: Context, name: String, strict: Boolean = true): Any? =
        resolveBinding(ctx, name, strict)?.value

    private fun resolveBinding(ctx: Context, name: String, strict: Boolean): ServiceBinding? {
        // Root and root-derived contexts do not represent a plugin dependency
        // boundary, so they resolve directly from the selected realm.
        if (ctx.fiber.runtime == null) return getBinding(ctx, name, strict)

        val error = IllegalStateException("cannot get property \"$name\" without inject")
        val realm = ctx.isolateLabels[name]
        var fiber = ctx.fiber
        while (true) {
            fiber.service(name)?.let { return it }
            if (fiber.inject.containsKey(name)) {
                throw IllegalStateException("cannot get required service \"$name\" in inactive context")
            }
            if (fiber.runtime == null) throw error
            if (fiber.parent.isolateLabels[name] !== realm) throw error
            fiber = fiber.parent.fiber
        }
    }

    fun <T> set(ctx: Context, key: ServiceKey<T>, value: T?): Boolean {
        val name = key.name
        val definition = synchronized(lock) { declarations[name] }
        if (definition is ContextDeclaration.Computed) return false
        if (definition is ContextDeclaration.Service && definition.key !== key) {
            throw IllegalStateException("service \"$name\" was set with a different ServiceKey instance")
        }
        if (definition == null && ctx.fiber.runtime != null) {
            throw IllegalStateException("cannot set property \"$name\" without provide")
        }
        val realm = ctx.isolateLabels[name]
            ?: throw IllegalStateException("cannot set property \"$name\" without provide")
        val binding = synchronized(lock) { bindings[realm] }
            ?: throw IllegalStateException("cannot set property \"$name\" without provide")
        if (binding.fiber !== ctx.fiber) {
            throw IllegalStateException("cannot set property \"$name\" in multiple fibers")
        }
        binding.value = value
        return true
    }

    fun <T> set(ctx: Context, property: ContextProperty<T>, value: T?): Boolean {
        val definition = synchronized(lock) { declarations[property.name] }
        if (definition !is ContextDeclaration.Computed) return false
        check(definition.property === property) {
            "property \"${property.name}\" was set with a different ContextProperty instance"
        }
        val setter = property.set ?: return false
        return setter(ctx, value)
    }

    internal fun getBinding(ctx: Context, name: String, strict: Boolean = true): ServiceBinding? {
        val realm = ctx.isolateLabels[name] ?: return null
        val binding = synchronized(lock) { bindings[realm] } ?: return null
        if (strict && binding.fiber.state != FiberState.ACTIVE) return null
        return binding
    }

    internal fun providers(fiber: Fiber<*>): List<ServiceBinding> = synchronized(lock) {
        bindings.values.filter { it.fiber === fiber }
    }

    /** Moves a loader-managed provider when its Entry changes inherited realm. */
    internal fun relocateProvider(name: String, oldRealm: Any?, newRealm: Any?, owner: Context): Boolean {
        if (oldRealm == null || newRealm == null || oldRealm === newRealm) return false
        return synchronized(lock) {
            val binding = bindings[oldRealm] ?: return@synchronized false
            if (binding.name != name || !binding.fiber.ctx.isDescendantOf(owner)) return@synchronized false
            if (bindings.containsKey(newRealm)) return@synchronized false
            bindings.remove(oldRealm)
            bindings[newRealm] = binding
            binding.realm = newRealm
            true
        }
    }

    fun <T> provide(
        ctx: Context,
        key: ServiceKey<T>,
        value: T? = null,
        check: ((Context) -> Boolean)? = null,
    ): EffectHandle = provideErased(ctx, key, value, check)

    internal fun provideService(
        ctx: Context,
        key: ServiceKey<*>,
        value: Service<*>,
        check: ((Context) -> Boolean)? = null,
    ): EffectHandle = provideErased(ctx, key, value, check)

    private fun provideErased(
        ctx: Context,
        key: ServiceKey<*>,
        value: Any?,
        check: ((Context) -> Boolean)?,
    ): EffectHandle {
        val name = key.name
        return ctx.effect("ctx.provide(\"$name\")") {
        synchronized(lock) {
            val previous = declarations[name]
            if (previous is ContextDeclaration.Computed) {
                throw IllegalStateException("property \"$name\" is already declared as computed")
            }
            if (previous is ContextDeclaration.Service && previous.key !== key) {
                throw IllegalStateException("service \"$name\" is already declared with a different ServiceKey instance")
            }
            declarations[name] = ContextDeclaration.Service(key)
        }

        root.isolateLabels.putIfAbsent(name, ServiceRealm(name))
        val realm = checkNotNull(ctx.isolateLabels[name])
        val binding = ServiceBinding(key, realm, ctx.fiber, value, check)
        synchronized(lock) {
            val previous = bindings[realm]
            if (previous != null) {
                throw IllegalStateException("service \"$name\" has been registered at <${previous.fiber.name}>")
            }
            bindings[realm] = binding
        }
        ctx.fiber.install(binding)
        if (ctx.fiber.state == FiberState.ACTIVE) notifyChange(ctx, listOf(name))

        collect {
            val finalRealm = synchronized(lock) {
                val registeredRealm = bindings.pairs().firstOrNull { (_, value) -> value === binding }?.first
                    ?: binding.realm
                if (bindings[registeredRealm] === binding) bindings.remove(registeredRealm)
                registeredRealm
            }
            val fibers = notifyChange(ctx, listOf(name)) { target, targetName ->
                target.isolateLabels[targetName] === finalRealm
            }
            fibers.forEach { it.await() }
            // The provider remains readable from its own Fiber until every
            // dependent has completed cleanup.
            ctx.fiber.removeService(name)
        }
    }
    }

    fun notifyChange(
        ctx: Context,
        names: List<String>,
        filter: (Context, String) -> Boolean = { target, name ->
            target.isolateLabels[name] === ctx.isolateLabels[name]
        },
    ): List<Fiber<*>> {
        val fibers = mutableListOf<Fiber<*>>()
        root.registry.values().forEach { runtime ->
            runtime.fibers.snapshot().forEach { fiber ->
                var changed = false
                names.forEach { name ->
                    if (!fiber.inject.containsKey(name) || !filter(fiber.ctx, name)) return@forEach
                    changed = true
                    fiber.checkBinding(name)
                }
                if (changed) {
                    fiber.refresh()
                    fibers += fiber
                }
            }
        }
        names.forEach { name ->
            val eventContext = ctx.extend {
                eventFilter = { target -> filter(target, name) }
            }
            root.emitEvent(eventContext, CoreEvents.Service, ServiceChange(name, getBinding(ctx, name, false)?.value))
        }
        return fibers
    }

    fun <T> register(ctx: Context, property: ContextProperty<T>): EffectHandle {
        val name = property.name
        return ctx.effect("ctx.property(\"$name\")") {
            synchronized(lock) {
                if (declarations.containsKey(name)) {
                    throw IllegalStateException("property \"$name\" is already declared")
                }
                declarations[name] = ContextDeclaration.Computed(property)
            }
            collect { synchronized(lock) { declarations.remove(name) }; Unit }
        }
    }
}
