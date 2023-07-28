package com.fithub.forax.concurrent.constant;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public sealed interface ComputedConstant<V> extends Supplier<V> permits IndexedComputedConstant {
  V get();
  V orElse(V other);
  <X extends Throwable> V orElseThrow(Supplier<? extends X> exceptionSupplier) throws X;

  boolean isBinding();
  boolean isBound();
  boolean isError();
  boolean isUnbound();

  default <R> ComputedConstant<R> map(Function<? super V,? extends R> mapper) {
    Objects.requireNonNull(mapper);
    return of(() -> mapper.apply(get()));
  }

  static <V> ComputedConstant<V> of(Supplier<? extends V> presetSupplier) {
    Objects.requireNonNull(presetSupplier);
    return ComputedConstant.<V>ofList(1, __ -> presetSupplier.get()).get(0);
  }

  //static <V> ComputedConstant<V> ofEmpty() {}
  static <V> List<ComputedConstant<V>> ofList(int size, IntFunction<? extends V> presetMapper) {
    Objects.requireNonNull(presetMapper);
    if (size == 0) {
      return List.of();
    }
    var states = IntStream.range(0, size).mapToObj(__ -> null).toList();
    return IntStream.range(0, size).<ComputedConstant<V>>mapToObj(i -> new IndexedComputedConstant<>(states, i, presetMapper, new ReentrantLock())).toList();
  }
}
