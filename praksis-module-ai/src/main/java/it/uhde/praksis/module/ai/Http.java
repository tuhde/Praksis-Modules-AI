// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * JSON over HTTP to a model server. Redirects are not followed (a key must not be sent to an
 * address that the person did not write down), and an answer is read only up to a limit.
 */
final class Http {
  static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_ANSWER = 8 << 20;

  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private Http() {}

  /** Posts the body as JSON and returns the answer, or throws with what the server said. */
  static JsonNode post(URI uri, Object body, Optional<String> key, int timeoutMillis)
      throws CapabilityException {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofMillis(timeoutMillis))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      key.ifPresent(k -> request.header("Authorization", "Bearer " + k));
      return answer(uri, request.build());
    } catch (IOException e) {
      throw failure(uri, e);
    }
  }

  static JsonNode get(URI uri, Optional<String> key, int timeoutMillis) throws CapabilityException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMillis)).GET();
    key.ifPresent(k -> request.header("Authorization", "Bearer " + k));
    try {
      return answer(uri, request.build());
    } catch (IOException e) {
      throw failure(uri, e);
    }
  }

  private static JsonNode answer(URI uri, HttpRequest request)
      throws IOException, CapabilityException {
    HttpResponse<InputStream> response;
    try {
      response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CapabilityException("the call to " + uri.getHost() + " was interrupted", true, e);
    }
    byte[] bytes;
    try (InputStream in = response.body()) {
      bytes = in.readNBytes(MAX_ANSWER + 1);
    }
    if (bytes.length > MAX_ANSWER) {
      throw new CapabilityException(
          "the answer of " + uri.getHost() + " is larger than " + (MAX_ANSWER >> 20) + " MiB",
          false);
    }
    int status = response.statusCode();
    JsonNode body;
    try {
      body = bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes);
    } catch (IOException e) {
      body = JSON.nullNode();
      if (status / 100 == 2) {
        throw new CapabilityException(
            "the answer of " + uri.getHost() + " is not JSON (is the url right?)", false, e);
      }
    }
    if (status / 100 == 2) {
      return body;
    }
    String said = said(body, bytes);
    if (status == 401 || status == 403) {
      throw new CapabilityException(
          uri.getHost() + " refused the credential (HTTP " + status + ")" + said, false);
    }
    boolean again = status == 429 || status / 100 == 5 || status == 408;
    throw new CapabilityException(uri.getHost() + " answered HTTP " + status + said, again);
  }

  /** What the server said about the error, short and without control characters. */
  private static String said(JsonNode body, byte[] raw) {
    String text = null;
    JsonNode error = body.get("error");
    if (error != null && error.isTextual()) {
      text = error.asText();
    } else if (error != null && error.hasNonNull("message")) {
      text = error.get("message").asText();
    } else if (body.isNull() && raw.length > 0) {
      text = new String(raw, 0, Math.min(raw.length, 200), StandardCharsets.UTF_8);
    }
    if (text == null || text.isBlank()) {
      return "";
    }
    String clean = text.replaceAll("\\p{Cntrl}", " ").strip();
    return ": " + (clean.length() > 200 ? clean.substring(0, 200) + "..." : clean);
  }

  private static CapabilityException failure(URI uri, IOException e) {
    if (e instanceof HttpTimeoutException) {
      return new CapabilityException("no answer from " + uri.getHost() + " in time", true, e);
    }
    return new CapabilityException(
        "cannot reach " + uri.getHost() + ":" + port(uri) + ": " + e.getClass().getSimpleName(),
        true,
        e);
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
  }
}
