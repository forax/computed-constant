package com.github.forax.concurrent.constant;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.TimeUnit;

// Benchmark                              Mode  Cnt  Score    Error  Units
// Benchmarks.static_constant_get_42      avgt    5  0.316 ±  0.001  ns/op
// Benchmarks.static_constant_get_null    avgt    5  0.316 ±  0.001  ns/op
// Benchmarks.static_field_init_get_42    avgt    5  0.315 ±  0.001  ns/op
// Benchmarks.static_field_init_get_null  avgt    5  0.316 ±  0.001  ns/op

// $JAVA_HOME/bin/java -jar target/benchmarks.jar -prof dtraceasm
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class Benchmarks {
  private static final Integer STATIC_CONSTANT_42 = 42;
  private static final Object STATIC_CONSTANT_NULL = null;

  private static final FieldInit FIELD_INIT = FieldInit.ofStatic(MethodHandles.lookup());

  private static Object $staticFieldInit$(String fieldName) {
    return switch (fieldName) {
      case "STATIC_FIELD_INIT_42" -> 42;
      case "STATIC_FIELD_INIT_NULL" -> null;
      default -> throw new AssertionError("unknown field " + fieldName);
    };
  }

  private static Integer STATIC_FIELD_INIT_42;
  private static Object STATIC_FIELD_INIT_NULL;

  @Benchmark
  public int static_constant_get_42() {
    return STATIC_CONSTANT_42;
  }

  @Benchmark
  public int static_field_init_get_42() {
    return FIELD_INIT.get("STATIC_FIELD_INIT_42");
  }

  @Benchmark
  public Object static_constant_get_null() {
    return STATIC_CONSTANT_NULL;
  }

  @Benchmark
  public Object static_field_init_get_null() {
    return FIELD_INIT.get("STATIC_FIELD_INIT_NULL");
  }
}
