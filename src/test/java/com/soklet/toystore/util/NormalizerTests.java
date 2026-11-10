package com.soklet.toystore.util;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class NormalizerTests {
 @Test void preservesSeparatorTrimmingSemantics() {
  assertEquals(Optional.empty(),Normalizer.trimAggressively(null));
  assertEquals(Optional.empty(),Normalizer.trimAggressively("\u00a0\u202f "));
  assertNull(Normalizer.trimAggressivelyToNull(""));
  assertNull(Normalizer.trimAggressivelyToNull("\t\r\n"));
  assertNull(Normalizer.trimAggressivelyToNull("\u00a0 "));
  assertEquals(Optional.of("\ttext\n"),Normalizer.trimAggressively(" \ttext\n "));
  assertEquals(Optional.of("text"),Normalizer.trimAggressively("\u202ftext\u00a0"));
 }
 @Test void interiorSpacesDoNotCauseQuadraticScanning() {
  String text="x"+" ".repeat(40_000)+"y";
  assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertEquals(Optional.of(text),Normalizer.trimAggressively(text)));
 }
}
