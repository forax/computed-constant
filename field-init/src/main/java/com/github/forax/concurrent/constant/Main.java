package com.github.forax.concurrent.constant;

import java.lang.invoke.MethodHandles;

public class Main {
  private static final FieldInit FIELD_INIT = FieldInit.ofStatic(MethodHandles.lookup());

  private static Object $staticFieldInit$(String fieldName) {
    return switch (fieldName) {
      case "X" -> 42;
      case "HELLO" -> "hello field init !";
      default -> throw new AssertionError("unknown " + fieldName);
    };
  }

  private static int X;
  private static String HELLO;

  public static void main(String[] args) {
    System.out.println("X: " + FIELD_INIT.getInt("X"));
    System.out.println("HELLO: " + FIELD_INIT.<String>get("HELLO"));
  }
}