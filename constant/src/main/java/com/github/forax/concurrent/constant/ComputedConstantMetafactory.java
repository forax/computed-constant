package com.github.forax.concurrent.constant;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.identity;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.throwException;
import static java.lang.invoke.MethodType.methodType;

public class ComputedConstantMetafactory {
  sealed interface State {
    enum Null implements State {
      NULL
    }
    record Error(Throwable throwable) implements State {}
  }

  public static <V> ComputedConstant<V> ofShim(MethodHandles.Lookup lookup, Class<?> declaringClass, String staticFieldName) {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(staticFieldName);
    return new StaticShimComputedConstant<>(lookup, declaringClass, staticFieldName);
  }

  @SuppressWarnings("unchecked")
  static <X extends Throwable> RuntimeException rethrow(Throwable throwable) throws X {
    throw (X) throwable;
  }

  private static final MethodHandle ALWAYS_THROW;
  static {
    var lookup = MethodHandles.lookup();
    try {
      ALWAYS_THROW = lookup.findStatic(ComputedConstantMetafactory.class, "alwaysThrow",
          methodType(void.class, Supplier.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  private static void alwaysThrow(Supplier<? extends Throwable> supplier) {
    Objects.requireNonNull(supplier);
    throw rethrow(supplier.get());
  }

  public static CallSite constantMethod(MethodHandles.Lookup lookup, String name, MethodType methodType, Object state) {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(name);
    Objects.requireNonNull(state);
    var returnType = methodType.returnType();
    return new ConstantCallSite(switch (name) {
      case "get" -> {
        if (state == State.Null.NULL) {
          yield constant(returnType, null);
        }
        if (state instanceof State.Error error) {
          yield insertArguments(throwException(returnType, Throwable.class), 0, error.throwable);
        }
        yield constant(state.getClass(), state).asType(methodType);
      }
      case "orElse" -> {
        if (state == State.Null.NULL) {
          yield dropArguments(constant(returnType, null),0, methodType.parameterType(0));
        }
        if (state instanceof State.Error error) {
          yield identity(returnType).asType(methodType);
        }
        yield dropArguments(constant(state.getClass(), state).asType(methodType), 0, methodType.parameterType(0));
      }
      case "orElseThrow" -> {
        if (state == State.Null.NULL) {
          yield dropArguments(constant(returnType, null),0, methodType.parameterType(0));
        }
        if (state instanceof State.Error error) {
          yield ALWAYS_THROW.asType(methodType);
        }
        yield dropArguments(constant(state.getClass(), state).asType(methodType), 0, methodType.parameterType(0));
      }
      default -> throw new LinkageError("unknown name " + name);
    });
  }

  static final ClassValue<ConcurrentHashMap<String, Object>> CLASS_VALUE = new ClassValue<ConcurrentHashMap<String, Object>>() {
    @Override
    protected ConcurrentHashMap<String, Object> computeValue(Class<?> type) {
      return new ConcurrentHashMap<>();
    }
  };

  static Object computeIfUnbound(ConcurrentHashMap<String, Object> map, String staticFieldName, MethodHandle staticInit) {
    return map.computeIfAbsent(staticFieldName, fieldName -> {
      try {
        return staticInit.invokeExact(fieldName);
      } catch (Throwable throwable) {
        return new State.Error(throwable);
      }
    });
  }

  public static Object constantState(MethodHandles.Lookup lookup, String name, Class<?> type, MethodHandle staticInit) throws Throwable {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(name);
    Objects.requireNonNull(type);
    Objects.requireNonNull(staticInit);
    return type.cast(computeIfUnbound(CLASS_VALUE.get(lookup.lookupClass()), name, staticInit));
  }
}
