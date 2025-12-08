/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.example.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.soklet.example.annotation.SensitiveValue;

import javax.annotation.Nonnull;
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
	@Nonnull
	private final Gson gson;
	@Nonnull
	private final ConcurrentHashMap<Class<?>, Set<String>> sensitivePathsCache;

	@Inject
	public SensitiveValueRedactor(@Nonnull Gson gson) {
		requireNonNull(gson);

		this.gson = gson;
		this.sensitivePathsCache = new ConcurrentHashMap<>();
	}

	// Perform redactions by examining {@link SensitiveValue} annotations in {@code targetType}.
	// Supports nested fields of arbitrary depth
	@Nonnull
	public String performRedactions(@Nonnull String json,
																	@Nonnull Class<?> targetType) {
		requireNonNull(json);
		requireNonNull(targetType);

		Set<String> sensitivePaths = determineSensitivePaths(targetType);

		if (sensitivePaths.isEmpty())
			return json;

		try {
			JsonElement element = JsonParser.parseString(json);

			if (element.isJsonObject())
				redactRecursively(element.getAsJsonObject(), sensitivePaths, "");

			return getGson().toJson(element);
		} catch (JsonSyntaxException | IllegalStateException e) {
			return "[redacted - malformed JSON]";
		}
	}

	@Nonnull
	private Set<String> determineSensitivePaths(@Nonnull Class<?> containingType) {
		requireNonNull(containingType);

		return getSensitivePathsCache().computeIfAbsent(containingType, clazz -> {
			if (!clazz.isRecord())
				return Set.of();

			Set<String> paths = new LinkedHashSet<>();
			collectSensitivePathsRecursively(clazz, "", paths, new HashSet<>());
			return Set.copyOf(paths);
		});
	}

	private void collectSensitivePathsRecursively(@Nonnull Class<?> recordType,
																								@Nonnull String pathPrefix,
																								@Nonnull Set<String> accumulator,
																								@Nonnull Set<Class<?>> visited) {
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

	private void redactRecursively(@Nonnull JsonObject jsonObject,
																 @Nonnull Set<String> sensitivePaths,
																 @Nonnull String currentPath) {
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

	@Nonnull
	private Gson getGson() {
		return this.gson;
	}

	@Nonnull
	private ConcurrentHashMap<Class<?>, Set<String>> getSensitivePathsCache() {
		return this.sensitivePathsCache;
	}
}