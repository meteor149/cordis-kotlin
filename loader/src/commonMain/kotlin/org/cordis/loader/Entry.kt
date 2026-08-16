package org.cordis.loader

import org.cordis.Context
import org.cordis.AttributeKey
import org.cordis.Fiber
import org.cordis.Plugin
import org.cordis.withConfiguredServices
import org.cordis.asDynamicPlugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

data class EntryOptions(
    var id: String = "",
    var name: String,
    var config: Any? = null,
    var group: Boolean? = null,
    var disabled: Boolean? = null,
    var inject: Map<String, Any?>? = null,
    var intercept: Map<String, Any?>? = null,
    var isolate: IsolationConfig? = null,
    var extra: Map<String, Any?> = emptyMap(),
)

sealed interface IsolationRule {
    data object Local : IsolationRule
    data class Shared(val realm: String) : IsolationRule
}

typealias IsolationConfig = Map<String, IsolationRule>

fun localIsolation(vararg services: org.cordis.ServiceKey<*>): IsolationConfig =
    services.associate { it.name to IsolationRule.Local }

fun sharedIsolation(realm: String, vararg services: org.cordis.ServiceKey<*>): IsolationConfig =
    services.associate { it.name to IsolationRule.Shared(realm) }

sealed interface FieldPatch<out T> {
    data object Keep : FieldPatch<Nothing>
    data class Set<T>(val value: T) : FieldPatch<T>
}

fun <T> changeTo(value: T): FieldPatch<T> = FieldPatch.Set(value)

private inline fun <T> FieldPatch<T>.ifSet(block: (T) -> Unit) {
    if (this is FieldPatch.Set) block(value)
}

data class EntryPatch(
    val name: FieldPatch<String> = FieldPatch.Keep,
    val config: FieldPatch<Any?> = FieldPatch.Keep,
    val group: FieldPatch<Boolean?> = FieldPatch.Keep,
    val disabled: FieldPatch<Boolean?> = FieldPatch.Keep,
    val inject: FieldPatch<Map<String, Any?>?> = FieldPatch.Keep,
    val intercept: FieldPatch<Map<String, Any?>?> = FieldPatch.Keep,
    val isolate: FieldPatch<IsolationConfig?> = FieldPatch.Keep,
)

class Entry(val loader: Loader) {
    val ctx: Context = loader.ctx.extend { attributes[ATTRIBUTE] = this@Entry }
    var fiber: Fiber<Any?>? = null
    lateinit var parent: EntryGroup
    lateinit var options: EntryOptions
    var subgroup: EntryGroup? = null
    var subtree: EntryTree? = null
    var realm: LocalRealm? = null
    private var initialization: CompletableDeferred<Unit>? = null
    internal val initTask: Job? get() = initialization
    private val initLock = SynchronizedObject()
    private var managedIsolationNames: Set<String> = emptySet()
    private var pendingIsolationTokens: Map<String, Any?> = emptyMap()

    val context get() = ctx
    val id: String get() {
        val outer = parent.tree.ctx.attributes[ATTRIBUTE]
        return if (outer == null) options.id else "${outer.id}${EntryTree.SEP}${options.id}"
    }
    val disabled: Boolean get() {
        if (options.group == true) return false
        var current: Entry? = this
        while (current != null) {
            if (current.options.disabled == true) return true
            current = current.parent.ctx.attributes[ATTRIBUTE]
        }
        return false
    }

    fun evaluate(expression: String): Any? = org.cordis.loader.evaluate(ctx, expression)

    suspend fun refresh() {
        if (fiber?.uid == null && !disabled) init()
    }

    suspend fun update(patch: EntryPatch, force: Boolean = false) {
        val previous = options.copy()
        patch.name.ifSet { options.name = it }
        patch.config.ifSet { options.config = it }
        patch.group.ifSet { options.group = it }
        patch.disabled.ifSet { options.disabled = it }
        patch.inject.ifSet { options.inject = it }
        patch.intercept.ifSet { options.intercept = it }
        patch.isolate.ifSet { options.isolate = it }
        loader.releaseIsolation(previous.isolate)
        val configChanged = !deepEqual(options.config, previous.config)
        val changed = force || configChanged || options.id != previous.id || options.name != previous.name ||
            options.group != previous.group || options.disabled != previous.disabled || options.inject != previous.inject ||
            options.intercept != previous.intercept || options.isolate != previous.isolate
        if (disabled) {
            fiber?.dispose(); fiber = null
            return
        }
        if (fiber?.uid != null && changed) {
            ctx.emitEvent(LoaderEvents.PartialDispose, PartialDispose(this, previous, true))
        }
        patchContext()
        if (fiber?.uid != null && changed && (configChanged || options.group == true)) {
            fiber!!.update(resolveConfig())
        } else if (fiber?.uid == null) {
            init()
        }
    }

    suspend fun update(options: EntryOptions, create: Boolean, force: Boolean = false) {
        if (create) this.options = options
        update(EntryPatch(), force)
    }

    private fun patchContext() {
        ctx.replaceIntercept(options.intercept.orEmpty().mapKeys { org.cordis.ServiceReference(it.key) })
        val names = managedIsolationNames + options.isolate.orEmpty().keys +
            pendingIsolationTokens.keys + ctx.isolationNames().map { it.name }
        val oldTokens = names.associateWith { name ->
            if (pendingIsolationTokens.containsKey(name)) pendingIsolationTokens[name]
            else ctx.isolationToken(org.cordis.ServiceReference(name))
        }
        val isolation = linkedMapOf<String, Any>()
        options.isolate.orEmpty().forEach { (name, rule) ->
            val token = when (rule) {
                IsolationRule.Local -> (realm ?: LocalRealm(this).also { realm = it }).access(name, true)
                is IsolationRule.Shared -> loader.globalRealm(rule.realm).access(name, true)
            }
            isolation[name] = token
        }
        ctx.replaceIsolation(isolation.mapKeys { org.cordis.ServiceReference(it.key) })
        names.forEach { name ->
            val oldToken = oldTokens[name]
            val newToken = ctx.isolationToken(org.cordis.ServiceReference(name))
            if (oldToken === newToken) return@forEach
            ctx.relocateService(org.cordis.ServiceReference(name), oldToken, newToken)
        }
        managedIsolationNames = isolation.keys
        pendingIsolationTokens = emptyMap()
    }

    internal fun rebase(parent: Context) {
        val names = ctx.isolationNames() + parent.isolationNames()
        pendingIsolationTokens = names.associate { it.name to ctx.isolationToken(it) }
        ctx.rebase(parent)
    }

    private fun resolveConfig(): Any? = if (options.group == true) options.config else interpolate(ctx, options.config)

    private fun deepEqual(left: Any?, right: Any?): Boolean = when {
        left is Array<*> && right is Array<*> -> left.contentDeepEquals(right)
        left is ByteArray && right is ByteArray -> left.contentEquals(right)
        left is ShortArray && right is ShortArray -> left.contentEquals(right)
        left is IntArray && right is IntArray -> left.contentEquals(right)
        left is LongArray && right is LongArray -> left.contentEquals(right)
        left is FloatArray && right is FloatArray -> left.contentEquals(right)
        left is DoubleArray && right is DoubleArray -> left.contentEquals(right)
        left is CharArray && right is CharArray -> left.contentEquals(right)
        left is BooleanArray && right is BooleanArray -> left.contentEquals(right)
        else -> left == right
    }

    suspend fun init() {
        var owner = false
        val task = synchronized(initLock) {
            initialization ?: CompletableDeferred<Unit>().also {
                initialization = it
                owner = true
            }
        }
        if (!owner) {
            task.await()
            return
        }
        try {
            initOnce()
            task.complete(Unit)
        } catch (error: Throwable) {
            task.completeExceptionally(error)
            throw error
        } finally {
            synchronized(initLock) { if (initialization === task) initialization = null }
            // Loader's service check may keep entries pending while another
            // entry is importing or loading. Re-evaluate those consumers once
            // the final tracked initialization task settles.
            if (fiber != null && loader.getTasks().isEmpty()) ctx.notifyServiceChange(Loader.Key)
        }
    }

    private suspend fun initOnce() {
        if (disabled || fiber?.uid != null) return
        val loaded = try {
            parent.tree.import(options.name)
        } catch (error: Throwable) {
            ctx.logger().error(error)
            return
        }
        val plugin = loader.unwrapExports(loaded).asDynamicPlugin()
            ?: throw IllegalArgumentException("module ${options.name} does not export a Cordis Plugin")
        patchContext()
        loader.showLog(this, "apply")
        val merged = if (options.inject.isNullOrEmpty()) plugin else object : Plugin<Any?> by plugin {
            override val inject = plugin.inject.withConfiguredServices(options.inject.orEmpty())
        }
        fiber = ctx.plugin(merged, resolveConfig()).also {
            it.attributes[ATTRIBUTE] = this
            it.ctx.attributes[ATTRIBUTE] = this
        }
        fiber!!.await()
    }

    companion object { val ATTRIBUTE = AttributeKey<Entry>("cordis.entry") }
}
