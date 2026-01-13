package com.thenoah.dev.mybatis_easy_starter.support.naming;

public final class NamingStrategyHolder {
  private static volatile NamingStrategy strategy = new DefaultNamingStrategy();

  public static NamingStrategy get() {
    return strategy;
  }

  public static void set(NamingStrategy s) {
    strategy = s;
  }
}
