package com.soklet.toystore.util;

import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class LoggingRedactorTests {
	@Test void jwtRedactionIsBoundedForAdversarialInput() {
		var converter = new LoggingRedactor();
		LoggingEvent token = new LoggingEvent();
		token.setMessage("Bearer eyJhbGci.eyJzdWIi.signature trailing");
		assertEquals("Bearer [REDACTED] trailing", converter.convert(token));
		LoggingEvent repeatedPrefix = new LoggingEvent();
		repeatedPrefix.setMessage("eyJ".repeat(333_333));
		assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> assertEquals(repeatedPrefix.getMessage(), converter.convert(repeatedPrefix)));
	}
}
