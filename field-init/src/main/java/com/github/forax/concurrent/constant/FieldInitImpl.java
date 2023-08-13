package com.github.forax.concurrent.constant;

import com.github.forax.concurrent.constant.FieldInitMetafactory.Computed;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.exactInvoker;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodType.methodType;

record FieldInitImpl(MethodHandle instanceMH, MethodHandle staticMH) implements FieldInit {

  public static MethodHandle createStaticMH(Lookup lookup) {
    // get shared class data
    var fieldInitData = FieldInitMetafactory.getFieldInitClassData(lookup);

    // get static field init method
    var staticFieldInit = fieldInitData.staticFieldInit(lookup);
    return new StaticInliningCache(lookup, staticFieldInit, fieldInitData.computedMap).dynamicInvoker();
  }

  public static MethodHandle createInstanceMH(Lookup lookup) {
    // get shared class data
    var fieldInitData = FieldInitMetafactory.getFieldInitClassData(lookup);

    // get instance field init method
    var instanceFieldInit = fieldInitData.instanceFieldInit(lookup);

    var instanceMH = new InstanceInliningCache(lookup, instanceFieldInit).dynamicInvoker();
    return instanceMH.asType(methodType(Object.class, Object.class, String.class));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(String fieldName) {
    Objects.requireNonNull(fieldName);
    if (staticMH == null) {
      throw new UnsupportedOperationException();
    }
    try {
      return (T) staticMH.invokeExact(fieldName);
    } catch (Throwable e) {
      throw rethrow(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(Object instance, String fieldName) {
    Objects.requireNonNull(instance);
    Objects.requireNonNull(fieldName);
    if (instanceMH == null) {
      throw new UnsupportedOperationException();
    }
    try {
      return (T) instanceMH.invokeExact(instance, fieldName);
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

    private Object staticFallback(String fieldName) throws Throwable {
      // 1. fieldName is a constant string ?
      if (fieldName != fieldName.intern()) {
        throw new LinkageError("the field name is not an interned string");
      }

      // 2. find static field
      var staticVarHandle = FieldInitMetafactory.findStaticVarHandle(lookup, fieldName);

      // 3. compute the value
      var value = FieldInitMetafactory.computeStaticIfUnbound(computedMap, fieldName, fieldInit, staticVarHandle);

      // 4. install the guard
      var target = dropArguments(
          constant(Object.class, value),
          0, String.class);
      var test = STRING_CHECK.bindTo(fieldName);
      var guard = MethodHandles.guardWithTest(test,
          target,
          new StaticInliningCache(lookup, fieldInit, computedMap).dynamicInvoker());
      setTarget(guard);
      return value;
    }
  }

  private static final class InstanceInliningCache extends MutableCallSite {
    private static final MethodHandle STRING_CHECK, INSTANCE_FALLBACK;
    static {
      var lookup = MethodHandles.lookup();
      try {
        STRING_CHECK = lookup.findStatic(InstanceInliningCache.class, "stringCheck",
            methodType(boolean.class, String.class, Object.class, String.class));
        INSTANCE_FALLBACK = lookup.findVirtual(InstanceInliningCache.class, "instanceFallback",
            methodType(MethodHandle.class, Object.class, String.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final Lookup lookup;
    private final MethodHandle fieldInit;

    public InstanceInliningCache(Lookup lookup, MethodHandle fieldInit) {
      super(fieldInit.type());
      this.lookup = lookup;
      this.fieldInit = fieldInit;
      var fallback = INSTANCE_FALLBACK.bindTo(this).asType(methodType(MethodHandle.class, type().parameterType(0), String.class));
      setTarget(foldArguments(exactInvoker(type()), fallback));
    }

    private static boolean stringCheck(String expected, Object instance, String argument) {
      return expected == argument;
    }

    private MethodHandle instanceFallback(Object instance, String fieldName) throws Throwable {
      // 1. fieldName is a constant string ?
      if (fieldName != fieldName.intern()) {
        throw new LinkageError("the field name is not an interned string");
      }

      // 2. find instance field
      var instanceVarHandle = FieldInitMetafactory.findInstanceVarHandle(lookup, fieldName);

      // 3. install the guard
      var receiverType = type().parameterType(0);
      var target = insertArguments(FieldInitMetafactory.COMPUTE_INSTANCE_IF_UNBOUND, 2, fieldInit, instanceVarHandle)
          .asType(methodType(Object.class, receiverType, String.class));
      var test = STRING_CHECK.bindTo(fieldName)
          .asType(methodType(boolean.class, receiverType, String.class));
      var guard = MethodHandles.guardWithTest(test,
          target,
          new InstanceInliningCache(lookup, fieldInit).dynamicInvoker());
      setTarget(guard);
      return target;
    }
  }
}
