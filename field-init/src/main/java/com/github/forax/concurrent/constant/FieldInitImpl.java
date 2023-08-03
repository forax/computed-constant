package com.github.forax.concurrent.constant;

import com.github.forax.concurrent.constant.FieldInitMetafactory.Computed;

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
    var fieldInitData = FieldInitMetafactory.getFieldInitData(lookup);

    var fieldInit = fieldInitData.staticFieldInit(lookup);
    return new StaticInliningCache(lookup, fieldInit, fieldInitData.computedMap).dynamicInvoker();
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
      var declaringClass = lookup.lookupClass();
      var field = Arrays.stream(declaringClass.getDeclaredFields())
          .filter(f -> f.getName().equals(fieldName))
          .findFirst()
          .orElseThrow(() -> new NoSuchFieldError("no such field " + fieldName + " in " + declaringClass.getName()));

      var fieldModifiers = field.getModifiers();
      if (!Modifier.isStatic(fieldModifiers)) {
        throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not declared static");
      }
      if (!Modifier.isPrivate(fieldModifiers)) {
        throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not declared private");
      }
      if (Modifier.isFinal(fieldModifiers)) {
        throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " should not be declared final");
      }

      // 3. compute value and set field
      var staticSetter = lookup.findStaticSetter(field.getDeclaringClass(), fieldName, field.getType());
      var value = FieldInitMetafactory.computeIfUnbound(computedMap, field.getName(), fieldInit, staticSetter);

      // 4. install the guard
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
