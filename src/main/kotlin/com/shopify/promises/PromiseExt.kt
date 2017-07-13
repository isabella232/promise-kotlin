@file:JvmName("PromiseExt")

package com.shopify.promises

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bind success resolved value of this [Promise]`<T, E>` and return a new [Promise]`<T1, E>` with transformed value
 *
 * @param transform transformation function `(T) -> T1`
 * @return [Promise]`<T1, E>`
 */
inline fun <T, E, T1> Promise<T, out E>.map(crossinline transform: (T) -> T1): Promise<T1, E> {
  return bind {
    when (it) {
      is Promise.Result.Error -> Promise.ofError<T1, E>(it.error)
      is Promise.Result.Success -> Promise.ofSuccess<T1, E>(transform(it.value))
    }
  }
}

/**
 * Bind success resolved value of this [Promise]`<T, E>` and return a new [Promise]`<T1, E>` with transformed value
 *
 * @param transform transformation function `(T) -> Promise<T1, E>`
 * @return [Promise]`<T1, E>`
 */
inline fun <T, E, T1> Promise<T, out E>.then(crossinline transform: (T) -> Promise<T1, E>): Promise<T1, E> {
  return bind {
    when (it) {
      is Promise.Result.Error -> Promise.ofError<T1, E>(it.error)
      is Promise.Result.Success -> transform(it.value)
    }
  }
}

/**
 * Bind error resolved value of this [Promise]`<T, E>` and return a new [Promise]`<T, E1>` with transformed value
 *
 * @param transform transformation function `(E) -> E1`
 * @return [Promise]`<T, E1>`
 */
inline fun <T, E, E1> Promise<out T, E>.mapError(crossinline transform: (E) -> E1): Promise<T, E1> {
  return bind {
    when (it) {
      is Promise.Result.Success -> Promise.ofSuccess<T, E1>(it.value)
      is Promise.Result.Error -> Promise.ofError<T, E1>(transform(it.error))
    }
  }
}

/**
 * Bind error resolved value of this [Promise]`<T, E>` and return a new [Promise]`<T, E1>` with transformed value
 *
 * @param transform transformation function `(E) -> Promise<T, E1>`
 * @return [Promise]`<T, E1>`
 */
inline fun <T, E, E1> Promise<out T, E>.errorThen(crossinline transform: (E) -> Promise<T, E1>): Promise<T, E1> {
  return bind {
    when (it) {
      is Promise.Result.Success -> Promise.ofSuccess<T, E1>(it.value)
      is Promise.Result.Error -> transform(it.error)
    }
  }
}

/**
 * Register action to be performed before executing this [Promise]
 *
 * @param block action to be performed `() -> Unit`
 * @return [Promise]`<T, E>`
 */
inline fun <T, E> Promise<T, E>.onStart(crossinline block: () -> Unit): Promise<T, E> {
  return Promise<Unit, Nothing> {
    block()
    onSuccess(Unit)
  }
    .promoteError<Unit, E>()
    .then { this }
}

/**
 * Schedule execution of this [Promise] on provided executor
 *
 * @param executor to be scheduled on
 * @return [Promise]`<T, E>`
 */
fun <T, E> Promise<T, E>.startOn(executor: Executor): Promise<T, E> {
  return Promise<Unit, Nothing> {
    val canceled = AtomicBoolean()
    doOnCancel {
      canceled.set(true)
    }
    executor.execute {
      if (!canceled.get()) onSuccess(Unit)
    }
  }
    .promoteError<Unit, E>()
    .then { this }
}

/**
 * Schedule delivering results of this promise on provide executor
 *
 * @param executor to be scheduled on
 * @return [Promise]`<T, E>`
 */
fun <T, E> Promise<T, E>.completeOn(executor: Executor): Promise<T, E> {
  return bind { result ->
    Promise<T, E> {
      val canceled = AtomicBoolean()
      doOnCancel {
        canceled.set(true)
      }
      executor.execute {
        if (!canceled.get()) {
          when (result) {
            is Promise.Result.Success -> onSuccess(result.value)
            is Promise.Result.Error -> onError(result.error)
          }
        }
      }
    }
  }
}

/**
 * Ignore error result of this [Promise]
 *
 * **NOTE: in case if this promise failed, new promise will be never resolved.**
 *
 * @return [Promise]`<T, Nothing>`
 */
fun <T, E> Promise<T, E>.ignoreError(): Promise<T, Nothing> {
  return this.errorThen {
    Promise.never<T, Nothing>()
  }
}

/**
 * Convenience method to transform this [Promise]`T, Nothing` that can be never resolved as error to a new one with typed error [Promise]`<T, E>`
 *
 * @return [Promise]`<T, E>`
 */
fun <T, E> Promise<T, Nothing>.promoteError(): Promise<T, E> {
  return mapError {
    throw IllegalStateException("should never happen")
  }
}

/**
 * Create promise that will be never executed
 *
 * @return [Promise]`<T, E>`
 */
fun <T, E> Promise.Companion.never(): Promise<T, E> = Promise<T, E> {}

/**
 * Create [Promise]`Array<T>, E` that will wait until all provided promises are successfully resolved or one of them fails
 *
 * If one of provided promises failed remaining promises will be terminated and this [Promise] will fail too with the same error.
 * Execution results are kept in order.
 *
 * @param promises sequence of [Promise]`<T, E>` to be executed
 * @return [Promise]`<Array<T>, E>`
 */
inline fun <reified T, E> Promise.Companion.all(promises: Sequence<Promise<T, E>>): Promise<Array<T>, E> {
  return Promise {
    val subscriber = this
    val promiseList = promises.toList()
    val remainingCount = AtomicInteger(promiseList.size)
    val canceled = AtomicBoolean()
    val cancel = {
      canceled.set(true)
      promiseList.forEach { it.cancel() }
    }
    doOnCancel(cancel)

    val result = Array<Any?>(promiseList.size, { Unit })
    promiseList.forEachIndexed { index, promise ->
      if (canceled.get()) return@forEachIndexed

      promise.whenComplete {
        when (it) {
          is Promise.Result.Success -> result[index] = it.value as Any?
          is Promise.Result.Error -> cancel().apply { subscriber.onError(it.error) }
        }

        if (remainingCount.decrementAndGet() == 0 && !canceled.get()) {
          subscriber.onSuccess(result.map { it as T }.toTypedArray())
        }
      }
    }
  }
}

/**
 * Create [Promise]`Array<T>, E` that will wait until all provided promises are successfully resolved or one of them fails
 *
 * If one of provided promises failed remaining promises will be terminated and this [Promise] will fail too with the same error.
 * Execution results are kept in order.
 *
 * @param promises array of [Promise]`<T, E>` to be executed
 * @return [Promise]`<Array<T>, E>`
 */
inline fun <reified T, E> Promise.Companion.all(vararg promises: Promise<T, E>): Promise<Array<T>, E> {
  return all(promises.asSequence())
}

/**
 * Create [Promise]`<Tuple<T1, T2>, E>` that will wait until all provided promises are success resolved or one of them fails
 *
 * If one of provided promises failed remaining promises will be terminated and this [Promise] will fail too with the same error.
 *
 * @param p1 [Promise]`<T1, E>` to be executed
 * @param p2 [Promise]`<T2, E>` to be executed
 * @return [Promise]`<Tuple<T1, T2>, E>`
 *
 * @see Tuple
 */
@Suppress("UNCHECKED_CAST", "NAME_SHADOWING")
fun <T1 : Any?, T2 : Any?, E> Promise.Companion.all(p1: Promise<T1, E>, p2: Promise<T2, E>): Promise<Tuple<T1, T2>, E> {
  val p1 = p1.map { it as Any? }
  val p2 = p2.map { it as Any? }
  return all<Any?, E>(p1, p2).map {
    Tuple(it[0] as T1, it[1] as T2)
  }
}

/**
 * Create [Promise]`<Tuple3<T1, T2, T3>, E>` that will wait until all provided promises are success resolved or one of them fails
 *
 * If one of provided promises failed remaining promises will be terminated and this [Promise] will fail too with the same error.
 *
 * @param p1 [Promise]`<T1, E>` to be executed
 * @param p2 [Promise]`<T2, E>` to be executed
 * @param p3 [Promise]`<T3, E>` to be executed
 * @return [Promise]`<Tuple3<T1, T2, T3>, E>`
 *
 * @see Tuple3
 */
@Suppress("UNCHECKED_CAST", "NAME_SHADOWING")
fun <T1 : Any?, T2 : Any?, T3 : Any?, E> Promise.Companion.all(p1: Promise<T1, E>, p2: Promise<T2, E>, p3: Promise<T3, E>): Promise<Tuple3<T1, T2, T3>, E> {
  val p1 = p1.map { it as Any? }
  val p2 = p2.map { it as Any? }
  val p3 = p3.map { it as Any? }
  return all<Any?, E>(p1, p2, p3).map {
    Tuple3(it[0] as T1, it[1] as T2, it[2] as T3)
  }
}

/**
 * Create [Promise]`<Tuple4<T1, T2, T3, T4>, E>` that will wait until all provided promises are success resolved or one of them fails
 *
 * If one of provided promises failed remaining promises will be terminated and this [Promise] will fail too with the same error.
 *
 * @param p1 [Promise]`<T1, E>` to be executed
 * @param p2 [Promise]`<T2, E>` to be executed
 * @param p3 [Promise]`<T3, E>` to be executed
 * @param p4 [Promise]`<T4, E>` to be executed
 * @return [Promise]`<Tuple4<T1, T2, T3, T4>, E>`
 *
 * @see Tuple4
 */
@Suppress("UNCHECKED_CAST", "NAME_SHADOWING")
fun <T1 : Any?, T2 : Any?, T3 : Any?, T4 : Any?, E> Promise.Companion.all(p1: Promise<T1, E>, p2: Promise<T2, E>, p3: Promise<T3, E>, p4: Promise<T4, E>): Promise<Tuple4<T1, T2, T3, T4>, E> {
  val p1 = p1.map { it as Any? }
  val p2 = p2.map { it as Any? }
  val p3 = p3.map { it as Any? }
  val p4 = p4.map { it as Any? }
  return all<Any?, E>(p1, p2, p3, p4).map {
    Tuple4(it[0] as T1, it[1] as T2, it[2] as T3, it[3] as T4)
  }
}

/**
 * Create [Promise] that will be resolved as soon as the first of the provided promises resolved
 *
 * If resolved [Promise] resolved with error this one will fail with the same error.
 *
 * @param promises sequence of [Promise]`<T, E>` to be resolved
 * @return [Promise]`<T, E>`
 */
inline fun <reified T, E> Promise.Companion.any(promises: Sequence<Promise<T, E>>): Promise<T, E> {
  return Promise {
    val subscriber = this
    val promiseList = promises.toList()
    val canceled = AtomicBoolean()
    val cancel = {
      canceled.set(true)
      promiseList.forEach { it.cancel() }
    }
    doOnCancel(cancel)

    promiseList.forEach {
      if (canceled.get()) return@forEach
      it.whenComplete {
        when (it) {
          is Promise.Result.Success -> {
            cancel()
            subscriber.onSuccess(it.value)
          }
          is Promise.Result.Error -> {
            cancel()
            subscriber.onError(it.error)
          }
        }
      }
    }
  }
}

/**
 * Create [Promise] that will be resolved as soon as the first of the provided promises resolved
 *
 * If resolved [Promise] resolved with error this one will fail with the same error.
 *
 * @param promises vararg of [Promise]`<T, E>` to be resolved
 * @return [Promise]`<T, E>`
 */
inline fun <reified T, E> Promise.Companion.any(vararg promises: Promise<T, E>): Promise<T, E> {
  return any(promises.asSequence())
}
