package com.shopify.promises

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Policies that define the way [Promise] execution must be retried
 */
sealed class RetryPolicy {
  /**
   * Signals to not retry [Promise] execution again
   */
  object Cancel : RetryPolicy()

  /**
   * Signals to retry [Promise] execution immediately, without any delay
   */
  object Immediately : RetryPolicy()

  /**
   * Signals to retry [Promise] execution later after some delay
   */
  class WithDelay(val delay: Long, val timeUnit: TimeUnit) : RetryPolicy() {
    val delayMs = timeUnit.toMillis(delay)
  }
}

/**
 * Handler provides [RetryPolicy] that defines if [Promise] execution result from previous attempt requires another retry
 */
typealias RetryHandler<T, E> = (Int, Promise.Result<T, E>) -> RetryPolicy

/**
 * Default retry handlers
 */
sealed class DefaultRetryHandler {

  /**
   * Simple delayed based retry handler
   */
  class Delayed(private val delay: Long, private val timeUnit: TimeUnit) : DefaultRetryHandler() {
    private var maxCount = 0
    private var backoffMultiplier = 1f

    /**
     * The maximum amount of times to retry the [Promise] execution
     */
    fun maxCount(value: Int) = apply { maxCount = Math.max(value, 0) }

    /**
     * The exponential factor to use to calculate the delay
     */
    fun backoffMultiplier(value: Float) = apply { backoffMultiplier = value }

    fun build(): RetryHandler<out Any, out Any> {
      return { attempt, result ->
        val delay = delayForAttempt(attempt, timeUnit.toMillis(delay), backoffMultiplier)
        when {
          (result is Promise.Result.Success) -> RetryPolicy.Cancel
          (maxCount > 0 && attempt > maxCount) -> RetryPolicy.Cancel
          (delay > 0) -> RetryPolicy.WithDelay(delay, TimeUnit.MILLISECONDS)
          else -> RetryPolicy.Immediately
        }
      }
    }
  }

  /**
   * Delayed retry handler with optional condition to check if the result of previous attempt of [Promise] execution requires another
   * attempt
   */
  class Conditional<T, E>(private val delay: Long, private val timeUnit: TimeUnit) : DefaultRetryHandler() {
    private var maxCount = 0
    private var backoffMultiplier = 1f
    private var condition: (Promise.Result<T, E>) -> Boolean = { it is Promise.Result.Error }

    /**
     * The maximum amount of times to retry the [Promise] execution
     */
    fun maxCount(value: Int) = apply { maxCount = Math.max(value, 0) }

    /**
     * The exponential factor to use to calculate the delay
     */
    fun backoffMultiplier(value: Float) = apply { backoffMultiplier = value }

    /**
     * The predicate to check if [Promise] result is not fulfilled and next retry attempt is required
     */
    fun retryWhen(condition: (Promise.Result<T, E>) -> Boolean) = apply { this.condition = condition }

    fun build(): RetryHandler<T, E> {
      return { attempt, result ->
        val delay = delayForAttempt(attempt, timeUnit.toMillis(delay), backoffMultiplier)
        when {
          !condition(result) -> RetryPolicy.Cancel
          (maxCount > 0 && attempt > maxCount) -> RetryPolicy.Cancel
          (delay > 0) -> RetryPolicy.WithDelay(delay, TimeUnit.MILLISECONDS)
          else -> RetryPolicy.Immediately
        }
      }
    }
  }

  protected fun delayForAttempt(attempt: Int, delay: Long, backoffMultiplier: Float): Long {
    return Math.max((delay * Math.pow(backoffMultiplier.toDouble(), attempt.toDouble()).toLong()), delay)
  }
}

/**
 * Generates [Promise]
 */
class PromiseGenerator<T, E>(val generate: () -> Promise<T, E>)

/**
 * Retry [Promise] execution generated by [PromiseGenerator] with provided retry handler
 *
 * @param shouldRetry handler to determine if [Promise] execution should be retried for the provided result of the previous attempt
 */
fun <T, E> PromiseGenerator<T, E>.retry(executor: ScheduledExecutorService, shouldRetry: RetryHandler<T, E>): Promise<T, E> {

  fun Promise.Subscriber<T, E>.dispatch(result: Promise.Result<T, E>) {
    when (result) {
      is Promise.Result.Success -> this.resolve(result.value)
      is Promise.Result.Error -> this.reject(result.error)
    }
  }

  fun Iterator<Promise<T, E>>.retry(attempt: Int, delay: Long, subscriber: Promise.Subscriber<T, E>) {
    this.next()
      .delayStart(delay, TimeUnit.MILLISECONDS, executor)
      .whenComplete { result ->
        val retry = shouldRetry(attempt, result)
        when (retry) {
          is RetryPolicy.Cancel -> subscriber.dispatch(result)
          is RetryPolicy.Immediately -> this.retry(attempt = attempt + 1, delay = 0, subscriber = subscriber)
          is RetryPolicy.WithDelay -> this.retry(attempt = attempt + 1, delay = retry.delayMs, subscriber = subscriber)
        }
      }
  }

  return Promise<T, E> {
    val upstreamPromise = AtomicReference<Promise<T, E>>(null)

    val canceled = AtomicBoolean()
    onCancel {
      canceled.set(true)
      upstreamPromise.get()?.cancel()
    }

    generateSequence { generate() }
      .onEach { upstreamPromise.set(it) }
      .onEach { if (canceled.get()) it.cancel() }
      .iterator()
      .retry(attempt = 0, delay = 0, subscriber = this)
  }
}
