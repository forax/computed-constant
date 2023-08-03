package com.github.forax.concurrent.constant;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.invoke.MethodType.methodType;

public class FieldInitMetafactory {
  private record State(Throwable throwable, Object result) {}

  static final class Computed {
    // 2 states: null -> STATE

    private State state;
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

  private static Object callStaticFieldInit(String fieldName, MethodHandle fieldInit) throws Throwable {
    try {
      return fieldInit.invokeExact(fieldName);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static Object computeIfUnbound(ConcurrentHashMap<String, Computed> computedMap, String fieldName, MethodHandle fieldInit, MethodHandle staticSetter) throws Throwable {
    var computed = computedMap.computeIfAbsent(fieldName, __ -> new Computed());
    synchronized (computed) {
      if (computed.state == null) {
        try {
          // 1. call static field init
          var value = callStaticFieldInit(fieldName, fieldInit);

          // 2. set static value
          if (staticSetter != null) {
            staticSetter.invoke(value);
          }

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

  static final class FieldInitData {
    final ConcurrentHashMap<String, Computed> computedMap = new ConcurrentHashMap<>();
    private MethodHandle staticFieldInit;  // cached

    public MethodHandle staticFieldInit(Lookup lookup) {
      var staticFieldInit = this.staticFieldInit;
      if (staticFieldInit != null) {
        return staticFieldInit;
      }
      return this.staticFieldInit = findStaticFieldInit(lookup);
    }
  }

  private static final ClassValue<FieldInitData> FIELD_INIT_DATA_CLASS_VALUE = new ClassValue<>() {
    @Override
    protected FieldInitData computeValue(Class<?> type) {
      return new FieldInitData();
    }
  };

  static FieldInitData getFieldInitData(Lookup lookup) throws IllegalAccessError {
    if ((lookup.lookupModes() & (Lookup.PRIVATE | Lookup.ORIGINAL)) == 0) {
      throw new IllegalAccessError("wrong lookup access mode " + lookup.lookupModes());
    }
    return FIELD_INIT_DATA_CLASS_VALUE.get(lookup.lookupClass());
  }


  enum FieldInitNotImplemented implements FieldInit {
    INSTANCE;

    @Override
    public <T> T get(String fieldName) {
      throw new UnsupportedOperationException("get() is not supported");
    }
  }

  public static FieldInit ofNotImplemented(Lookup lookup) {
    return FieldInitNotImplemented.INSTANCE;
  }

  public static Object staticFieldInit(Lookup lookup, String name, Class<?> type) throws Throwable {
    var fieldInitData = getFieldInitData(lookup);
    var staticFieldInit = fieldInitData.staticFieldInit(lookup);
    return computeIfUnbound(fieldInitData.computedMap, name, staticFieldInit, null);
  }
}
