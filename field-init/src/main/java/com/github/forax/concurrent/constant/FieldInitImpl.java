package com.github.forax.concurrent.constant;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodType.methodType;

record FieldInitImpl(MethodHandle mh) implements FieldInit {
  public static MethodHandle createStaticMH(Lookup lookup) {
    var fieldInit = findStaticFieldInit(lookup);
    var computedMap = new ConcurrentHashMap<String, StaticInliningCache.Computed>();
    return new StaticInliningCache(lookup, fieldInit, computedMap).dynamicInvoker();
  }

  private static MethodHandle findStaticFieldInit(Lookup lookup) {
    try {
      return lookup.findStatic(lookup.lookupClass(), "$staticFieldInit$", methodType(Object.class, String.class));
    } catch (NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(String fieldName) {
    try {
      return (T) mh.invokeExact(fieldName);
    } catch (Throwable e) {
      throw rethrow(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static <X extends Throwable> RuntimeException rethrow(Throwable throwable) throws X {
    throw (X) throwable;
  }

  private static final class StaticInliningCache extends MutableCallSite {
    private static final MethodHandle STRING_CHECK, STATIC_FALLBACK;
    static {
      var lookup = MethodHandles.lookup();
      try {
        STRING_CHECK = lookup.findStatic(StaticInliningCache.class, "stringCheck",
            methodType(boolean.class, String.class, String.class));
        STATIC_FALLBACK = lookup.findVirtual(StaticInliningCache.class, "staticFallback",
            methodType(Object.class, String.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private record State(Throwable throwable, Object result) {}

    private static final class Computed {
      // 2 states: null -> STATE

      State state;
    }

    private final ConcurrentHashMap<String, Computed> computedMap;

    private final Lookup lookup;
    private final MethodHandle fieldInit;

    public StaticInliningCache(Lookup lookup, MethodHandle fieldInit, ConcurrentHashMap<String, Computed> computedMap) {
      super(fieldInit.type());
      this.lookup = lookup;
      this.fieldInit = fieldInit;
      this.computedMap = computedMap;
      setTarget(STATIC_FALLBACK.bindTo(this));
    }

    private static boolean stringCheck(String expected, String argument) {
      return expected == argument;
    }

    private Object computeIfUnbound(Field field, MethodHandle fieldInit) throws Throwable {
      var fieldName = field.getName();
      var computed = computedMap.computeIfAbsent(fieldName, __ -> new Computed());
      synchronized (computed) {
        if (computed.state == null) {
          try {
            // 1. call static field init
            var value = callStaticFieldInit(fieldName, fieldInit);

            // 2. set static value
            var staticSetter = lookup.findStaticSetter(field.getDeclaringClass(), fieldName, field.getType());
            staticSetter.invoke(value);

            computed.state = new State(null, value);
            return value;
          } catch (Throwable throwable) {
            computed.state = new State(throwable, null);
            throw throwable;
          }
        }
        if (computed.state.throwable != null) {
          throw computed.state.throwable;
        }
        return computed.state.result;
      }
    }

    private static Object callStaticFieldInit(String fieldName, MethodHandle fieldInit) throws Throwable {
      try {
        return fieldInit.invokeExact(fieldName);
      } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    private Object staticFallback(String fieldName) throws Throwable {
      // 1. find static field
      var declaringClass = lookup.lookupClass();
      var field = Arrays.stream(declaringClass.getDeclaredFields())
          .filter(f -> f.getName().equals(fieldName))
          .findFirst()
          .orElseThrow(() -> new NoSuchFieldError("no such field " + fieldName + " in " + declaringClass.getName()));

      var fieldModifiers = field.getModifiers();
      if (!Modifier.isStatic(fieldModifiers)) {
        throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not declared static");
      }
      if (Modifier.isFinal(fieldModifiers)) {
        throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " should not be declared final");
      }

      // 2. compute value and set field
      var value = computeIfUnbound(field, fieldInit);

      // 3. install the guard
      var target = dropArguments(
          constant(field.getType(), value).asType(methodType(Object.class)),
          0, String.class);
      var test = STRING_CHECK.bindTo(fieldName);
      var guard = MethodHandles.guardWithTest(test,
          target,
          new StaticInliningCache(lookup, fieldInit, computedMap).dynamicInvoker());
      setTarget(guard);
      return value;
    }
  }
}
