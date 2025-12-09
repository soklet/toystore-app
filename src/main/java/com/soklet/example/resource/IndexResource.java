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

package com.soklet.example.resource;

import com.google.inject.Inject;
import com.lokalized.Strings;
import com.soklet.MarshaledResponse;
import com.soklet.annotation.GET;
import com.soklet.annotation.PathParameter;
import com.soklet.example.exception.NotFoundException;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class IndexResource {
	@Nonnull
	private final Strings strings;

	@Inject
	public IndexResource(@Nonnull Strings strings) {
		requireNonNull(strings);
		this.strings = strings;
	}

	@Nonnull
	@GET("/")
	public MarshaledResponse indexPage() throws IOException {
		Path indexPageFile = Path.of("web/index.html");

		if (!Files.isRegularFile(indexPageFile))
			throw new IllegalStateException(format("Unable to load index page from %s", indexPageFile.toAbsolutePath()));

		byte[] indexPageFileContents = Files.readAllBytes(indexPageFile);

		// By returning MarshaledResponse instead of Response,
		// we are saying "I already know how to turn my response into bytes,
		// so please don't perform extra processing on it (e.g. turn it into JSON)"
		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/html;charset=UTF-8")))
				.body(indexPageFileContents)
				.build();
	}

	@Nonnull
	@GET("/health-check")
	public MarshaledResponse healthCheck() {
		// Simple "OK" response
		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/plain;charset=UTF-8")))
				.body(getStrings().get("OK").getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@GET("/static/{staticFilePath*}")
	public MarshaledResponse staticFile(@Nonnull @PathParameter String staticFilePath) throws IOException {
		String contentType = "application/octet-stream";

		if (staticFilePath.endsWith(".js"))
			contentType = "text/js;charset=UTF-8";
		else if (staticFilePath.endsWith(".css"))
			contentType = "text/css;charset=UTF-8";

		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of(contentType)))
				.body(safelyReadUserProvidedPath(staticFilePath, Paths.get("web/static")))
				.build();
	}

	@Nonnull
	private byte[] safelyReadUserProvidedPath(@Nonnull String userProvidedPath,
																						@Nonnull Path sandboxDirectory) throws IOException {
		requireNonNull(userProvidedPath);
		requireNonNull(sandboxDirectory);

		// Resolve the user-supplied path relative to the public directory sandbox, prevents attacks like "../../etc/password" as user-entered filename
		Path realSandboxDirectory = sandboxDirectory.toRealPath();
		Path resolvedPath = realSandboxDirectory.resolve(userProvidedPath).normalize();

		if (!resolvedPath.startsWith(realSandboxDirectory))
			throw new SecurityException(format("Illegal attempt to access a file outside of %s (user-provided filename was %s)", sandboxDirectory, userProvidedPath));

		if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath))
			throw new NotFoundException(format("Unable to resolve file in public directory at %s (user-provided filename was %s)", resolvedPath.toAbsolutePath(), userProvidedPath));

		return Files.readAllBytes(resolvedPath);
	}

	@Nonnull
	private Strings getStrings() {
		return this.strings;
	}
}