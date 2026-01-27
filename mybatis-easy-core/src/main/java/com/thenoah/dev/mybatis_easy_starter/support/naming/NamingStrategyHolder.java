package com.thenoah.dev.mybatis_easy_starter.support.naming;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class NamingStrategyHolder {

  private static final NamingStrategy DEFAULT = new DefaultNamingStrategy();
  private static final AtomicReference<NamingStrategy> REF = new AtomicReference<>(DEFAULT);

  private NamingStrategyHolder() {}

  public static NamingStrategy get() {
    NamingStrategy s = REF.get();
    return (s != null) ? s : DEFAULT;
  }

  /**
   * 전역 교체
   * - Spring AutoConfiguration에서 "부팅 시 1회" 세팅하는 정도로만 사용 권장
   */
  public static void set(NamingStrategy s) {
    REF.set(Objects.requireNonNull(s, "NamingStrategy must not be null"));
  }

  /**
   * 스코프형 교체: try-with-resources로 원복 보장
   * 예)
   * try (var ignored = NamingStrategyHolder.withStrategy(new X())) { ... }
   */
  public static AutoCloseable withStrategy(NamingStrategy s) {
    Objects.requireNonNull(s, "NamingStrategy must not be null");
    NamingStrategy prev = get();
    set(s);
    return () -> set(prev);
  }

  public static void resetToDefault() {
    REF.set(DEFAULT);
  }
}
