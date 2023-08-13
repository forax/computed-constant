package com.github.forax.concurrent.constant;

import com.github.forax.concurrent.constant.ComputedConstantMetafactory.State;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Fat Instance of computed constant used if the computed constant need to be materialized.
 *
 * @param <V> type of the value
 */
final class StaticShimComputedConstant<V> implements ComputedConstant<V> {
  private final ConcurrentHashMap<String, Object> concurrentHashMap;
  private final String staticFieldName;
  private final MethodHandle staticFieldInit;

  public StaticShimComputedConstant(MethodHandles.Lookup lookup, Class<?> declaringClass, String staticFieldName) {
    this.concurrentHashMap = ComputedConstantMetafactory.CLASS_VALUE.get(declaringClass);
    this.staticFieldName = staticFieldName;
    // Trying to get the $staticInit$ early allows to check that the calling lookup can access
    // to the declaring class internals
    this.staticFieldInit = findStaticFieldInit(declaringClass, lookup);
  }

  private static MethodHandle findStaticFieldInit(Class<?> declaringClass, MethodHandles.Lookup lookup) {
    try {
      return lookup.findStatic(declaringClass, "$staticInit$", MethodType.methodType(Object.class, String.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public V get() {
    var state = ComputedConstantMetafactory.computeIfUnbound(concurrentHashMap, staticFieldName, staticFieldInit);
    if (state == State.Null.NULL) {
      return null;
    }
    if (state instanceof State.Error error) {
      throw ComputedConstantMetafactory.rethrow(error.throwable());
    }
    return (V) state;
  }

  @Override
  @SuppressWarnings("unchecked")
  public V orElse(V other) {
    var state = ComputedConstantMetafactory.computeIfUnbound(concurrentHashMap, staticFieldName, staticFieldInit);
    if (state == State.Null.NULL) {
      return null;
    }
    if (state instanceof State.Error) {
      return other;
    }
    return (V) state;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <X extends Throwable> V orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
    var state = ComputedConstantMetafactory.computeIfUnbound(concurrentHashMap, staticFieldName, staticFieldInit);
    if (state == State.Null.NULL) {
      return null;
    }
    if (state instanceof State.Error) {
      throw exceptionSupplier.get();
    }
    return (V) state;
  }

  @Override
  public boolean isBinding() {
    return concurrentHashMap.get(staticFieldName) == null && concurrentHashMap.containsKey(staticFieldName);
  }

  @Override
  public boolean isBound() {
    return concurrentHashMap.get(staticFieldName) != null;
  }

  @Override
  public boolean isError() {
    return concurrentHashMap.get(staticFieldName) instanceof State.Error;
  }

  @Override
  public boolean isUnbound() {
    return concurrentHashMap.get(staticFieldName) == null && !concurrentHashMap.containsKey(staticFieldName);
  }
}
