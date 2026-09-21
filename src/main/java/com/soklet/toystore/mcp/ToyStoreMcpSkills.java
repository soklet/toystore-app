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
import com.soklet.McpSkillBundle;
import com.soklet.McpSkillGroup;
import com.soklet.McpSkillRegistration;
import com.soklet.McpSkillVariantSelector;
import com.soklet.toystore.model.db.Account;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Authored, immutable guidance for the existing read-only catalog operations. */
public final class ToyStoreMcpSkills {
	private ToyStoreMcpSkills() {
	}

	@NonNull
	public static McpSkillGroup catalogGuideGroup() {
		// Only these six packaged application files are published. No directory
		// scan or request-supplied language can change the resource paths.
		return McpSkillGroup.fromKeyAndSkillRegistrations("toy-catalog-guide", List.of(
				catalogGuide(Locale.US, "skill://toystore/v1/en-US/toy-catalog-guide/SKILL.md",
						"/mcp/skills/en-US/toy-catalog-guide/SKILL.md",
						"/mcp/skills/en-US/toy-catalog-guide/references/catalog-fields.md"),
				catalogGuide(Locale.GERMANY, "skill://toystore/v1/de-DE/toy-catalog-guide/SKILL.md",
						"/mcp/skills/de-DE/toy-catalog-guide/SKILL.md",
						"/mcp/skills/de-DE/toy-catalog-guide/references/catalog-fields.md"),
				catalogGuide(Locale.forLanguageTag("pt-BR"), "skill://toystore/v1/pt-BR/toy-catalog-guide/SKILL.md",
						"/mcp/skills/pt-BR/toy-catalog-guide/SKILL.md",
						"/mcp/skills/pt-BR/toy-catalog-guide/references/catalog-fields.md")));
	}

	/** Account language chooses presentation, never permission to read a variant. */
	@NonNull
	public static McpSkillVariantSelector catalogGuideSelector(@NonNull Strings strings) {
		requireNonNull(strings);
		return (requestContext, selection, features) -> {
			if (!selection.getSkillGroupKey().equals("toy-catalog-guide"))
				return Optional.empty();
			Object principal = requestContext.getAdmissionIdentity().getPrincipal().orElse(null);
			if (!(principal instanceof Account account))
				return Optional.empty();
			// Positive client language preferences do not override the account.
			// Explicit exclusions still constrain discovery, including fallback.
			Locale preferred = strings.bestMatchFor(account.locale());
			List<McpSkillRegistration> candidates = selection.getSkillRegistrations();
			List<Locale.LanguageRange> ranges = selection.getLanguageRanges();
			return eligibleCandidate(candidates, preferred, ranges)
					.or(() -> eligibleCandidate(candidates, Locale.US, ranges));
		};
	}

	@NonNull
	private static Optional<McpSkillRegistration> eligibleCandidate(
			@NonNull List<McpSkillRegistration> candidates, @NonNull Locale locale,
			@NonNull List<Locale.LanguageRange> ranges) {
		if (excluded(locale, ranges))
			return Optional.empty();
		// Return the exact supplied candidate, not an equal registration copy.
		return candidates.stream().filter(candidate -> candidate.getLocale()
				.filter(locale::equals).isPresent()).findFirst();
	}

	/** RFC 4647 basic matching: most-specific match wins, then earliest range. */
	private static boolean excluded(@NonNull Locale locale,
			@NonNull List<Locale.LanguageRange> ranges) {
		String tag = locale.toLanguageTag().toLowerCase(Locale.ROOT);
		int specificity = -1;
		boolean excluded = false;
		for (Locale.LanguageRange range : ranges) {
			String value = range.getRange();
			int length = value.equals("*") ? 0 : value.length();
			if (length <= specificity)
				continue;
			if (length == 0 || tag.equals(value) || tag.startsWith(value + "-")) {
				specificity = length;
				excluded = range.getWeight() == 0;
			}
		}
		return excluded;
	}

	@NonNull
	private static McpSkillRegistration catalogGuide(@NonNull Locale locale,
			@NonNull String uri, @NonNull String guidePath, @NonNull String referencePath) {
		McpSkillBundle bundle = McpSkillBundle.fromFiles(Map.of(
				"SKILL.md", requiredResource(guidePath),
				"references/catalog-fields.md", requiredResource(referencePath)));
		return McpSkillRegistration.withUriAndSkillBundle(URI.create(uri), bundle)
				.locale(locale)
				// Keep the default private, zero-TTL policy. Admission still runs on every request.
				.build();
	}

	@NonNull
	private static byte[] requiredResource(@NonNull String name) {
		try (InputStream input = ToyStoreMcpSkills.class.getResourceAsStream(name)) {
			if (input == null)
				throw new IOException("Missing packaged Toy Store Skill resource: " + name);
			return input.readAllBytes();
		} catch (IOException exception) {
			throw new UncheckedIOException("Unable to load the Toy Store catalog guide.", exception);
		}
	}
}
