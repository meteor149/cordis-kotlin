@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.cordis.timer

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Clock
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectHandle
import org.cordis.Plugin
import org.cordis.Service
import org.cordis.ServiceKey
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TimerService(ctx: Context) : Service<Unit>(ctx, Key) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun timeout(owner: Context, callback: () -> Unit, delayMillis: Long): EffectHandle {
        lateinit var handle: EffectHandle
        handle = owner.effect("ctx.timeout()") {
            val job = scope.launch {
                delay(delayMillis.coerceAtLeast(0))
                if (isActive) {
                    handle.dispose()
                    callback()
                }
            }
            collect { job.cancel() }
        }
        return handle
    }

    suspend fun timeout(owner: Context, delayMillis: Long) {
        suspendCancellableCoroutine { continuation ->
            val completed = atomic(false)
            lateinit var handle: EffectHandle
            handle = owner.effect("ctx.timeout()") {
                val job = scope.launch {
                    delay(delayMillis.coerceAtLeast(0))
                    if (completed.compareAndSet(false, true)) continuation.resume(Unit)
                    handle.dispose()
                }
                collect {
                    job.cancel()
                    if (completed.compareAndSet(false, true)) {
                        continuation.resumeWithException(CancellationException("Context has been disposed"))
                    }
                }
            }
            continuation.invokeOnCancellation { scope.launch { handle.dispose() } }
        }
    }

    fun interval(owner: Context, callback: () -> Unit, delayMillis: Long): EffectHandle =
        owner.effect("ctx.interval()") {
            val job = scope.launch {
                val period = delayMillis.coerceAtLeast(1)
                while (isActive) {
                    delay(period)
                    if (isActive) callback()
                }
            }
            collect { job.cancel() }
        }

    fun interval(owner: Context, delayMillis: Long): Flow<Unit> = callbackFlow {
        val handle = interval(owner, { trySend(Unit) }, delayMillis)
        val lifecycle = owner.effect("ctx.interval(flow)") {
            collect { close(CancellationException("Context has been disposed")); Unit }
        }
        awaitClose { scope.launch { lifecycle.dispose(); handle.dispose() } }
    }

    fun throttle(
        owner: Context,
        callback: () -> Unit,
        delayMillis: Long,
        noTrailing: Boolean = false,
    ): ScheduledCall<Unit> = throttleValue(owner, { callback() }, delayMillis, noTrailing)

    fun <T> throttleValue(
        owner: Context,
        callback: (T) -> Unit,
        delayMillis: Long,
        noTrailing: Boolean = false,
    ): ScheduledCall<T> {
        var lastCall = Long.MIN_VALUE / 2
        return ScheduledCall(owner, scope, "ctx.throttle()", noTrailing) { value, disposed, schedule ->
            val now = Clock.System.now().toEpochMilliseconds()
            val remaining = delayMillis - now + lastCall
            if (remaining <= 0) {
                lastCall = now
                callback(value)
            } else if (!disposed) {
                schedule(remaining) {
                    lastCall = Clock.System.now().toEpochMilliseconds()
                    callback(value)
                }
            }
        }
    }

    fun debounce(owner: Context, callback: () -> Unit, delayMillis: Long): ScheduledCall<Unit> =
        debounceValue(owner, { callback() }, delayMillis)

    fun <T> debounceValue(owner: Context, callback: (T) -> Unit, delayMillis: Long): ScheduledCall<T> =
        ScheduledCall(owner, scope, "ctx.debounce()") { value, disposed, schedule ->
            if (!disposed) schedule(delayMillis) { callback(value) }
        }

    internal fun close() = scope.cancel()

    companion object {
        val Key = ServiceKey<TimerService>("timer")
    }
}

class ScheduledCall<T> internal constructor(
    owner: Context,
    private val scope: CoroutineScope,
    label: String,
    initiallyDisposed: Boolean = false,
    private val trigger: (T, Boolean, (Long, () -> Unit) -> Unit) -> Unit,
) : Disposable {
    private val disposed = atomic(initiallyDisposed)
    private val job = atomic<Job?>(null)
    private val handle = owner.effect(label) { collect { disposeTimer() } }

    operator fun invoke(value: T) {
        job.getAndSet(null)?.cancel()
        trigger(value, disposed.value) { wait, callback ->
            val next = scope.launch {
                delay(wait.coerceAtLeast(0))
                if (isActive && !disposed.value) callback()
            }
            job.getAndSet(next)?.cancel()
        }
    }

    override suspend fun dispose() = handle.dispose()
    private fun disposeTimer() {
        if (disposed.compareAndSet(false, true)) job.getAndSet(null)?.cancel()
    }
}

operator fun ScheduledCall<Unit>.invoke() = invoke(Unit)

object TimerPlugin : Plugin<Unit> {
    override val name = "timer"
    override suspend fun apply(ctx: Context, config: Unit, effect: org.cordis.EffectScope) {
        val timer = TimerService(ctx)
        effect.collect { timer.close() }
    }
}

fun Context.timeout(callback: () -> Unit, delayMillis: Long): EffectHandle = timer().timeout(this, callback, delayMillis)
suspend fun Context.timeout(delayMillis: Long) = timer().timeout(this, delayMillis)
fun Context.interval(callback: () -> Unit, delayMillis: Long): EffectHandle = timer().interval(this, callback, delayMillis)
fun Context.interval(delayMillis: Long): Flow<Unit> = timer().interval(this, delayMillis)
fun Context.throttle(callback: () -> Unit, delayMillis: Long, noTrailing: Boolean = false): ScheduledCall<Unit> =
    timer().throttle(this, callback, delayMillis, noTrailing)
fun <T> Context.throttleValue(
    callback: (T) -> Unit,
    delayMillis: Long,
    noTrailing: Boolean = false,
): ScheduledCall<T> = timer().throttleValue(this, callback, delayMillis, noTrailing)
fun Context.debounce(callback: () -> Unit, delayMillis: Long): ScheduledCall<Unit> =
    timer().debounce(this, callback, delayMillis)
fun <T> Context.debounceValue(callback: (T) -> Unit, delayMillis: Long): ScheduledCall<T> =
    timer().debounceValue(this, callback, delayMillis)

private fun Context.timer(): TimerService = require(TimerService.Key)
