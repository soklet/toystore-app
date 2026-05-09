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

import com.soklet.HttpMethod;
import com.soklet.MarshaledResponse;
import com.soklet.MarshaledResponseBody;
import com.soklet.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Set;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class IndexResourceTests {
	@Test
	public void staticFileUsesStaticFilesHelper() {
		IndexResource indexResource = new IndexResource();

		MarshaledResponse cssResponse = indexResource.staticFile(Request.fromPath(HttpMethod.GET, "/static/css/demo.css"), "css/demo.css");
		Assertions.assertEquals(200, cssResponse.getStatusCode());
		Assertions.assertEquals(Set.of("text/css; charset=UTF-8"), cssResponse.getHeaders().get("Content-Type"));
		Assertions.assertEquals(Set.of("bytes"), cssResponse.getHeaders().get("Accept-Ranges"));
		Assertions.assertTrue(cssResponse.getHeaders().containsKey("ETag"));
		Assertions.assertTrue(cssResponse.getHeaders().containsKey("Last-Modified"));
		Assertions.assertTrue(cssResponse.getBody().orElseThrow() instanceof MarshaledResponseBody.File);

		MarshaledResponse missingResponse = indexResource.staticFile(Request.fromPath(HttpMethod.GET, "/static/missing.css"), "missing.css");
		Assertions.assertEquals(404, missingResponse.getStatusCode());
	}
}
