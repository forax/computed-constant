package com.fithub.forax.concurrent.constant;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.HashSet;
import java.util.concurrent.TimeUnit;

// Benchmark                            Mode  Cnt  Score    Error  Units
// Benchmarks.static_computed_get_42    avgt    5  0.316 ±  0.001  ns/op
// Benchmarks.static_computed_get_null  avgt    5  0.315 ±  0.001  ns/op
// Benchmarks.static_constant_get_42    avgt    5  0.316 ±  0.003  ns/op
// Benchmarks.static_constant_get_null  avgt    5  0.316 ±  0.001  ns/op

// $JAVA_HOME/bin/java -jar target/benchmarks.jar -prof dtraceasm
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class Benchmarks {
  private static final Integer STATIC_CONSTANT_42 = 42;
  private static final ComputedConstant<Integer> STATIC_COMPUTED_42 = ComputedConstant.of(() -> 42);

  private static final Integer STATIC_CONSTANT_NULL = null;
  private static final ComputedConstant<Integer> STATIC_COMPUTED_NULL = ComputedConstant.of(() -> null);

  @Benchmark
  public int static_constant_get_42() {
    return STATIC_CONSTANT_42;
  }

  @Benchmark
  public int static_computed_get_42() {
    return STATIC_COMPUTED_42.get();
  }

  @Benchmark
  public Object static_constant_get_null() {
    return STATIC_CONSTANT_NULL;
  }

  @Benchmark
  public Object static_computed_get_null() {
    return STATIC_COMPUTED_NULL.get();
  }
}
