package com.github.forax.concurrent.constant;

public class Main {
  private static final ComputedConstant<String> TEXT =
      ComputedConstant.of(() -> "Hello");

  public static String message() {
    return TEXT.get();
  }

  static class Nested {
    public static String message2() {
      return TEXT.get();
    }
  }

  public static void main(String[] args) {
    System.out.println(message());
    System.out.println(TEXT.isBound());
    System.out.println(Nested.message2());
  }
}
