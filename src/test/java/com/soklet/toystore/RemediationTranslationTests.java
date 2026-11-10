package com.soklet.toystore;

import com.lokalized.LocalizedStringLoader;
import com.lokalized.Strings;
import com.lokalized.TranslationOptions;
import com.lokalized.TranslationResultStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RemediationTranslationTests {
	@Test void newValidationAndConflictErrorsHaveRealGermanAndPortugueseTranslations() {
		Strings strings = Strings.withFallbackLocale(Locale.forLanguageTag("en-US"))
				.localizedStringSupplier(() -> LocalizedStringLoader.loadFromFilesystem(Path.of("strings")))
				.localeSupplier(matcher -> matcher.bestMatchFor(Locale.forLanguageTag("en-US"))).build();
		Map<String, Map<String, String>> translations = Map.of(
				"de-DE", Map.of(
						"Name cannot exceed 256 characters.", "Der Name darf höchstens 256 Zeichen enthalten.",
						"Price cannot exceed 99999999.99.", "Der Preis darf 99999999.99 nicht überschreiten.",
						"A purchased toy cannot be deleted.", "Ein gekauftes Spielzeug kann nicht gelöscht werden."),
				"pt-BR", Map.of(
						"Name cannot exceed 256 characters.", "O nome não pode exceder 256 caracteres.",
						"Price cannot exceed 99999999.99.", "O preço não pode exceder 99999999.99.",
						"A purchased toy cannot be deleted.", "Um brinquedo comprado não pode ser excluído."));
		translations.forEach((locale, messages) -> messages.forEach((key, expected) -> {
			var result = strings.getResult(key, TranslationOptions.builder().locale(Locale.forLanguageTag(locale)).build());
			assertEquals(TranslationResultStatus.TRANSLATED, result.getStatus(), locale + ": " + key);
			assertEquals(expected, result.getTranslation());
		}));
	}
}
