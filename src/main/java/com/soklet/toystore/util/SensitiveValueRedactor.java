/*
 * Copyright 2022-2025 Revetware LLC.
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

package com.soklet.toystore.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.soklet.toystore.annotation.SensitiveValue;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * Given a JSON string and a type to map it to, redact any of the type's fields marked
 * with the {@link SensitiveValue} annotation (useful for request body logging).
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SensitiveValueRedactor {
	@NonNull
	private final Gson gson;
	@NonNull
	private final ConcurrentHashMap<@NonNull Class<?>, @NonNull Set<@NonNull String>> sensitivePathsCache;

	@Inject
	public SensitiveValueRedactor(@NonNull Gson gson) {
		requireNonNull(gson);

		this.gson = gson;
		this.sensitivePathsCache = new ConcurrentHashMap<>();
	}

	// Perform redactions by examining {@link SensitiveValue} annotations in {@code targetType}.
	// Supports nested fields of arbitrary depth
	@NonNull
	public String performRedactions(@NonNull String json,
																	@NonNull Class<?> targetType) {
		requireNonNull(json);
		requireNonNull(targetType);

		Set<@NonNull String> sensitivePaths = determineSensitivePaths(targetType);

		if (sensitivePaths.isEmpty())
			return json;

		try {
			JsonElement element = JsonParser.parseString(json);

			if (element.isJsonObject())
				redactRecursively(element.getAsJsonObject(), sensitivePaths, "");

			return getGson().toJson(element);
		} catch (JsonSyntaxException | IllegalStateException e) {
			return "[REDACTED - malformed JSON]";
		}
	}

	@NonNull
	private Set<@NonNull String> determineSensitivePaths(@NonNull Class<?> containingType) {
		requireNonNull(containingType);

		return getSensitivePathsCache().computeIfAbsent(containingType, clazz -> {
			if (!clazz.isRecord())
				return Set.of();

			Set<@NonNull String> paths = new LinkedHashSet<>();
			collectSensitivePathsRecursively(clazz, "", paths, new HashSet<>());
			return Set.copyOf(paths);
		});
	}

	private void collectSensitivePathsRecursively(@NonNull Class<?> recordType,
																								@NonNull String pathPrefix,
																								@NonNull Set<@NonNull String> accumulator,
																								@NonNull Set<@NonNull Class<?>> visited) {
		requireNonNull(recordType);
		requireNonNull(pathPrefix);
		requireNonNull(accumulator);
		requireNonNull(visited);

		if (!recordType.isRecord())
			return;

		// Prevent infinite recursion with self-referential types
		if (visited.contains(recordType))
			return;

		visited.add(recordType);

		for (RecordComponent component : recordType.getRecordComponents()) {
			String fieldPath = pathPrefix.isEmpty()
					? component.getName()
					: pathPrefix + "." + component.getName();

			if (component.isAnnotationPresent(SensitiveValue.class))
				accumulator.add(fieldPath);

			// Check for directly nested records
			Class<?> componentType = component.getType();

			if (componentType.isRecord()) {
				collectSensitivePathsRecursively(componentType, fieldPath, accumulator, visited);
			} else {
				// Check for parameterized types like List<SomeRecord>, Set<SomeRecord>, etc.
				Type genericType = component.getGenericType();

				if (genericType instanceof ParameterizedType parameterizedType)
					for (Type typeArg : parameterizedType.getActualTypeArguments())
						if (typeArg instanceof Class<?> typeArgClass && typeArgClass.isRecord())
							collectSensitivePathsRecursively(typeArgClass, fieldPath, accumulator, visited);
			}
		}
	}

	private void redactRecursively(@NonNull JsonObject jsonObject,
																 @NonNull Set<@NonNull String> sensitivePaths,
																 @NonNull String currentPath) {
		requireNonNull(jsonObject);
		requireNonNull(sensitivePaths);
		requireNonNull(currentPath);

		for (String key : new LinkedHashSet<>(jsonObject.keySet())) {
			String fieldPath = currentPath.isEmpty() ? key : currentPath + "." + key;
			JsonElement value = jsonObject.get(key);

			if (sensitivePaths.contains(fieldPath)) {
				jsonObject.addProperty(key, "[REDACTED]");
			} else if (value.isJsonObject()) {
				redactRecursively(value.getAsJsonObject(), sensitivePaths, fieldPath);
			} else if (value.isJsonArray()) {
				// Array elements share the same path since they're the same type
				for (JsonElement arrayElement : value.getAsJsonArray()) {
					if (arrayElement.isJsonObject())
						redactRecursively(arrayElement.getAsJsonObject(), sensitivePaths, fieldPath);
				}
			}
		}
	}

	@NonNull
	private Gson getGson() {
		return this.gson;
	}

	@NonNull
	private ConcurrentHashMap<@NonNull Class<?>, @NonNull Set<@NonNull String>> getSensitivePathsCache() {
		return this.sensitivePathsCache;
	}
}
