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

package com.soklet.toystore.resource;

import com.soklet.MarshaledResponse;
import com.soklet.MetricsCollector;
import com.soklet.MetricsCollector.MetricsFormat;
import com.soklet.MetricsCollector.SnapshotTextOptions;
import com.soklet.Request;
import com.soklet.StaticFiles;
import com.soklet.annotation.GET;
import com.soklet.annotation.PathParameter;
import com.soklet.toystore.annotation.SuppressRequestLogging;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static java.lang.String.format;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class IndexResource {
	@NonNull
	private final StaticFiles staticFiles;

	public IndexResource() {
		this.staticFiles = StaticFiles.withRoot(Path.of("web/static")).build();
	}

	@NonNull
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

	@NonNull
	@SuppressRequestLogging // Custom annotation to ignore standard request logging to reduce log noise
	@GET("/health-check")
	public MarshaledResponse healthCheck() {
		// Simple "OK" response
		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/plain;charset=UTF-8")))
				.body("OK".getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	@GET("/metrics")
	public MarshaledResponse getMetrics(@NonNull MetricsCollector metricsCollector) {
		SnapshotTextOptions snapshotTextOptions = SnapshotTextOptions.withMetricsFormat(MetricsFormat.PROMETHEUS).build();
		String body = metricsCollector.snapshotText(snapshotTextOptions).orElse(null);

		if (body == null)
			return MarshaledResponse.withStatusCode(204).build();

		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@NonNull
	@GET("/static/{staticFilePath*}")
	public MarshaledResponse staticFile(@NonNull Request request,
																			@NonNull @PathParameter String staticFilePath) {
		return this.staticFiles.marshaledResponseFor(staticFilePath, request)
				.orElseGet(() -> MarshaledResponse.fromStatusCode(404));
	}
}
