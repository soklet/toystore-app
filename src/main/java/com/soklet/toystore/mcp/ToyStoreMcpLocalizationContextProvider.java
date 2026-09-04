/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.toystore.mcp;

import com.lokalized.Strings;
import com.lokalized.TranslationFailureHandler;
import com.lokalized.TranslationFailureReason;
import com.lokalized.TranslationFallbackPolicy;
import com.lokalized.TranslationOptions;
import com.lokalized.TranslationResult;
import com.lokalized.TranslationResultStatus;
import com.soklet.McpLocalizationContext;
import com.soklet.McpLocalizationContextProvider;
import com.soklet.McpLocalizationRequest;
import com.soklet.McpLocalizationResult;
import com.soklet.McpLocalizationRevision;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Locale;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Adapts the application's already-loaded immutable Lokalized catalog snapshot
 * to Soklet's request-scoped MCP localization contract.
 */
@ThreadSafe
public final class ToyStoreMcpLocalizationContextProvider
		implements McpLocalizationContextProvider {
	@NonNull
	private static final McpLocalizationRevision REVISION =
			McpLocalizationRevision.fromValue("toystore-strings-v1");
	@NonNull
	private final Strings strings;

	public ToyStoreMcpLocalizationContextProvider(@NonNull Strings strings) {
		this.strings = requireNonNull(strings);
	}

	@Override
	@NonNull
	public McpLocalizationContext provideContext(
			@NonNull McpLocalizationRequest request) {
		requireNonNull(request);
		Locale locale = request.getContinuationLocale()
				.map(this::requireSupportedLocale)
				.orElseGet(() -> this.strings.bestMatchFor(
						request.getLanguageRanges()));
		TranslationOptions options = TranslationOptions.builder()
				.locale(locale)
				.translationFailureHandler(TranslationFailureHandler.returnKey())
				.translationFallbackPolicy(TranslationFallbackPolicy
						.fallbackOnMissingTranslationOrNoMatchingAlternative())
				.build();

		return McpLocalizationContext.withLocale(locale, text -> {
					try {
						TranslationResult result = requireNonNull(
								this.strings.getResult(
										requireNonNull(text).getDefaultText(),
										options));
						if (result.getStatus()
								== TranslationResultStatus.TRANSLATED)
							return McpLocalizationResult.localized(
									requireNonNull(result.getTranslation()));

						TranslationFailureReason reason =
								result.getFailureReason().orElseThrow();
						return switch (reason) {
							case MISSING_TRANSLATION,
									NO_MATCHING_ALTERNATIVE ->
									McpLocalizationResult.useDefaultText();
							case RESOLUTION_FAILURE ->
									McpLocalizationResult.failure();
						};
					} catch (RuntimeException exception) {
						return McpLocalizationResult.failure();
					}
				})
				.revision(REVISION)
				.build();
	}

	@NonNull
	private Locale requireSupportedLocale(@NonNull Locale requiredLocale) {
		String requiredTag = requireNonNull(requiredLocale).toLanguageTag();
		Set<Locale> supportedLocales = this.strings.getSupportedLocales();
		return supportedLocales.stream()
				.filter(locale -> locale.toLanguageTag()
						.equalsIgnoreCase(requiredTag))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"Required MCP continuation locale is unavailable."));
	}
}
