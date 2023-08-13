package com.github.forax.concurrent.constant;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.invoke.MethodType.methodType;

public class FieldInitMetafactory {
  static final class Computed {
    private Throwable error;  // bookkeeping failure if necessary
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
    Object result;
    try {
      result = fieldInit.invokeExact(fieldName);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    if (result == null) {
      throw new NullPointerException("static field init result for " + fieldName + " is null");
    }
    return result;
  }

  private static MethodHandle findInstanceFieldInit(Lookup lookup) {
    try {
      return lookup.findVirtual(lookup.lookupClass(), "$instanceFieldInit$", methodType(Object.class, String.class));
    } catch (NoSuchMethodException e) {
      throw (NoSuchMethodError) new NoSuchMethodError().initCause(e);
    } catch (IllegalAccessException e) {
      throw (IllegalAccessError) new IllegalAccessError().initCause(e);
    }
  }

  private static Object callInstanceFieldInit(Object instance, String fieldName, MethodHandle fieldInit) throws Throwable {
    Object result;
    try {
      result = fieldInit.invoke(instance, fieldName);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    if (result == null) {
      throw new NullPointerException("instance field init result for " + fieldName + " is null");
    }
    return result;
  }

  static Object computeStaticIfUnbound(ConcurrentHashMap<String, Computed> computedMap, String fieldName, MethodHandle staticFieldInit, VarHandle staticVarHandle) throws Throwable {
    // if another thread has initialized the value
    var value = staticVarHandle.getVolatile();
    if (value != null) {
      return value;
    }
    var computed = computedMap.computeIfAbsent(fieldName, __ -> new Computed());
    synchronized (computed) {
      // if another thread has initialized the value
      value = staticVarHandle.getVolatile();
      if (value != null) {
        // no bookkeeping is necessary anymore
        computedMap.remove(fieldName);
        return value;
      }

      // if another thread as run the static field init method and it failed
      var error = computed.error;
      if (error != null) {
        throw error;
      }

      try {
        // call the static field init method
        value = callStaticFieldInit(fieldName, staticFieldInit);

      } catch (Throwable throwable) {
        // keep the error
        computed.error = throwable;
        throw throwable;
      }

      // set static value
      staticVarHandle.setVolatile(value);

      // no bookkeeping needed anymore
      computedMap.remove(fieldName);

      return value;
    }
  }

  private static Object computeInstanceIfUnbound(Object instance, String fieldName, MethodHandle instanceFieldInit, VarHandle instanceVarHandle) throws Throwable {
    // if another thread has initialized the value
    var value = instanceVarHandle.getVolatile(instance);
    if (value != null) {
      return value;
    }
    synchronized (instance) {
      // if another thread has initialized the value
      value = instanceVarHandle.getVolatile(instance);
      if (value != null) {
        return value;
      }

      // call the instance field init method
      value = callInstanceFieldInit(instance, fieldName, instanceFieldInit);

      // set instance value
      instanceVarHandle.setVolatile(instance, value);

      return value;
    }
  }

  static final class FieldInitClassData {
    final ConcurrentHashMap<String, Computed> computedMap = new ConcurrentHashMap<>(4);
    private MethodHandle staticFieldInit;  // cached
    private MethodHandle instanceFieldInit;  // cached

    public MethodHandle staticFieldInit(Lookup lookup) {
      var staticFieldInit = this.staticFieldInit;
      if (staticFieldInit != null) {
        return staticFieldInit;
      }
      return this.staticFieldInit = findStaticFieldInit(lookup);
    }

    public MethodHandle instanceFieldInit(Lookup lookup) {
      var instanceFieldInit = this.instanceFieldInit;
      if (instanceFieldInit != null) {
        return instanceFieldInit;
      }
      return this.instanceFieldInit = findInstanceFieldInit(lookup);
    }
  }

  private static final ClassValue<FieldInitClassData> FIELD_INIT_DATA_CLASS_VALUE = new ClassValue<>() {
    @Override
    protected FieldInitClassData computeValue(Class<?> type) {
      return new FieldInitClassData();
    }
  };

  static FieldInitClassData getFieldInitClassData(Lookup lookup) throws IllegalAccessError {
    if ((lookup.lookupModes() & (Lookup.PRIVATE | Lookup.ORIGINAL)) == 0) {
      throw new IllegalAccessError("wrong lookup access mode " + lookup.lookupModes());
    }
    return FIELD_INIT_DATA_CLASS_VALUE.get(lookup.lookupClass());
  }


  private static final FieldInitImpl NOT_IMPLEMENTED = new FieldInitImpl(null, null);

  public static FieldInit ofNotImplemented(Lookup lookup) {
    return NOT_IMPLEMENTED;
  }


  static VarHandle findStaticVarHandle(Lookup lookup, String fieldName) throws IllegalAccessException {
    var declaringClass = lookup.lookupClass();
    var field = Arrays.stream(declaringClass.getDeclaredFields())
        .filter(f -> f.getName().equals(fieldName))
        .findFirst()
        .orElseThrow(() -> new NoSuchFieldError("no such field " + fieldName + " in " + declaringClass.getName()));

    if (field.getType().isPrimitive()) {
      throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not an object");
    }

    var fieldModifiers = field.getModifiers();
    if (!Modifier.isStatic(fieldModifiers)) {
      throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not declared static");
    }
    if (!Modifier.isPrivate(fieldModifiers)) {
      throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not declared private");
    }
    if (!Modifier.isVolatile(fieldModifiers)) {
      throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not declared volatile");
    }

    return lookup.unreflectVarHandle(field);
  }

  static VarHandle findInstanceVarHandle(Lookup lookup, String fieldName) throws IllegalAccessException {
    var declaringClass = lookup.lookupClass();
    var field = Arrays.stream(declaringClass.getDeclaredFields())
        .filter(f -> f.getName().equals(fieldName))
        .findFirst()
        .orElseThrow(() -> new NoSuchFieldError("no such field " + fieldName + " in " + declaringClass.getName()));

    if (field.getType().isPrimitive()) {
      throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not an object");
    }

    var fieldModifiers = field.getModifiers();
    if (Modifier.isStatic(fieldModifiers)) {
      throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " should not be declared static");
    }
    if (!Modifier.isPrivate(fieldModifiers)) {
      throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not declared private");
    }
    if (!Modifier.isVolatile(fieldModifiers)) {
      throw new NoSuchFieldError("field " + fieldName + " in " + declaringClass.getName() + " is not declared volatile");
    }

    return lookup.unreflectVarHandle(field);
  }

  public static Object staticFieldInit(Lookup lookup, String name, Class<?> type) throws Throwable {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(name);
    Objects.requireNonNull(type);

    // 1. get shared field init class data
    var fieldInitClassData = getFieldInitClassData(lookup);

    // 2. find static field init method
    var staticFieldInit = fieldInitClassData.staticFieldInit(lookup);

    // 3. find static field
    var staticVarHandle = findStaticVarHandle(lookup, name);

    // 4. compute value if not initialized
    return computeStaticIfUnbound(fieldInitClassData.computedMap, name, staticFieldInit, staticVarHandle);
  }

  static final MethodHandle COMPUTE_INSTANCE_IF_UNBOUND;
  static {
    var lookup = MethodHandles.lookup();
    try {
      COMPUTE_INSTANCE_IF_UNBOUND = lookup.findStatic(FieldInitMetafactory.class, "computeInstanceIfUnbound",
          methodType(Object.class, Object.class, String.class, MethodHandle.class, VarHandle.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public static CallSite instanceFieldInit(Lookup lookup, String name, MethodType type) throws Throwable {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(name);
    Objects.requireNonNull(type);

    // 1. get shared field init class data
    var fieldInitClassData = getFieldInitClassData(lookup);

    // 2. find instance field init method
    var instanceFieldInit = fieldInitClassData.instanceFieldInit(lookup);

    // 3. find instance field
    var instanceVarHandle = findInstanceVarHandle(lookup, name);

    // 4. install the target to computeInstanceIfUnbound
    var target = MethodHandles.insertArguments(COMPUTE_INSTANCE_IF_UNBOUND, 1, name, instanceFieldInit, instanceVarHandle);
    return new ConstantCallSite(target.asType(type));
  }
}
