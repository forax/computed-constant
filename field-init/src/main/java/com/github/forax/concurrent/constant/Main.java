package com.github.forax.concurrent.constant;

import java.lang.invoke.MethodHandles;

public class Main {
  private static final FieldInit FIELD_INIT = FieldInit.of(MethodHandles.lookup());

  private static Object $staticFieldInit$(String fieldName) {
    return switch (fieldName) {
      case "X" -> 42;
      case "HELLO" -> "hello static field init !";
      default -> throw new AssertionError("unknown " + fieldName);
    };
  }

  private static volatile Integer X;
  private static volatile String HELLO;


  private Object $instanceFieldInit$(String fieldName) {
    return switch (fieldName) {
      case "x" -> 42;
      case "hello" -> "hello instance field init !";
      default -> throw new AssertionError("unknown " + fieldName);
    };
  }

  private volatile Integer x;
  private volatile String hello;

  public static void main(String[] args) {
    System.out.println("X: " + FIELD_INIT.getInt("X"));
    System.out.println("HELLO: " + FIELD_INIT.<String>get("HELLO"));

    var main = new Main();
    System.out.println("x: " + FIELD_INIT.getInt(main, "x"));
    System.out.println("hello: " + FIELD_INIT.<String>get(main, "hello"));
  }
}