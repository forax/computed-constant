# computed-constant
A re-implementation of the computed constant API without modifying the VM

This is an implementation of the computed constant JEP (https://openjdk.org/jeps/8312611)
The code requires at least Java 17.

Because it does not use VM support, there is a small difference in terms of performance,
for an instance field of a class or an enum (but not a record), in that case, the JDK implementation should be
slightly more efficient.

Here are the benchmarks if the ComputedConstant is used as a static final compared to a true static final
on my laptop.
```
Benchmark                            Mode  Cnt  Score    Error  Units
Benchmarks.static_computed_get_42    avgt    5  0.316 ±  0.001  ns/op
Benchmarks.static_computed_get_null  avgt    5  0.315 ±  0.001  ns/op
Benchmarks.static_constant_get_42    avgt    5  0.316 ±  0.003  ns/op
Benchmarks.static_constant_get_null  avgt    5  0.316 ±  0.001  ns/op
```
