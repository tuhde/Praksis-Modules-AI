// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import com.fasterxml.jackson.databind.JsonNode;
import it.uhde.praksis.capability.CapabilityException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What the module needs from a model server, in the words of one of the two APIs. */
interface ModelApi {
  /** What a model answered, and what it cost. */
  record Reply(String text, long tokensIn, long tokensOut) {}

  /**
   * Asks the model. A {@code schema} (JSON Schema) makes the server keep the answer to it.
   *
   * @param key the key of the server, if it wants one
   */
  Reply chat(
      Profile profile,
      Optional<String> key,
      String system,
      String user,
      JsonNode schema,
      int maxTokens)
      throws CapabilityException;

  /** The embedding of a text. */
  List<Double> embed(Profile profile, Optional<String> key, String text) throws CapabilityException;

  /**
   * The address that a model really runs at, if the server says it forwards the model to another
   * machine; empty if it runs here or the server cannot tell.
   */
  default Optional<String> forwardedTo(Profile profile, Optional<String> key)
      throws CapabilityException {
    return Optional.empty();
  }

  static ModelApi of(Profile.Api api) {
    return switch (api) {
      case OLLAMA -> new Ollama();
      case OPENAI -> new OpenAi();
    };
  }

  private static URI path(URI base, String suffix) {
    String s = base.toString();
    return URI.create((s.endsWith("/") ? s.substring(0, s.length() - 1) : s) + suffix);
  }

  private static List<Double> numbers(JsonNode array) throws CapabilityException {
    if (array == null || !array.isArray() || array.isEmpty()) {
      throw new CapabilityException("the server sent no embedding", false);
    }
    List<Double> out = new ArrayList<>(array.size());
    for (JsonNode n : array) {
      out.add(n.asDouble());
    }
    return out;
  }

  /** Ollama: {@code /api/chat}, {@code /api/embed} and {@code /api/tags}. */
  final class Ollama implements ModelApi {
    @Override
    public Reply chat(
        Profile p, Optional<String> key, String system, String user, JsonNode schema, int maxTokens)
        throws CapabilityException {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", p.model());
      body.put(
          "messages",
          List.of(
              Map.of("role", "system", "content", system),
              Map.of("role", "user", "content", user)));
      body.put("stream", false);
      body.put("think", false); // a model that reasons first would use the budget up on that
      body.put("options", Map.of("temperature", p.temperature(), "num_predict", maxTokens));
      if (schema != null) {
        body.put("format", schema);
      }
      JsonNode answer = Http.post(path(p.url(), "/api/chat"), body, key, p.timeoutMillis());
      JsonNode content = answer.path("message").path("content");
      if (!content.isTextual()) {
        throw new CapabilityException("the server sent no answer (is the model right?)", false);
      }
      return new Reply(
          content.asText(),
          answer.path("prompt_eval_count").asLong(0),
          answer.path("eval_count").asLong(0));
    }

    @Override
    public List<Double> embed(Profile p, Optional<String> key, String text)
        throws CapabilityException {
      JsonNode answer =
          Http.post(
              path(p.url(), "/api/embed"),
              Map.of("model", p.embedModel().orElse(p.model()), "input", text),
              key,
              p.timeoutMillis());
      return numbers(answer.path("embeddings").path(0));
    }

    @Override
    public Optional<String> forwardedTo(Profile p, Optional<String> key)
        throws CapabilityException {
      JsonNode tags =
          Http.get(path(p.url(), "/api/tags"), key, Math.min(p.timeoutMillis(), 10_000));
      for (String model :
          p.embedModel().isPresent()
              ? List.of(p.model(), p.embedModel().get())
              : List.of(p.model())) {
        for (JsonNode m : tags.path("models")) {
          String name = m.path("name").asText(m.path("model").asText(""));
          if ((name.equals(model) || name.equals(model + ":latest"))
              && m.hasNonNull("remote_host")
              && !m.get("remote_host").asText().isBlank()) {
            return Optional.of(m.get("remote_host").asText());
          }
        }
      }
      return Optional.empty();
    }
  }

  /** The OpenAI interface that llama.cpp, vLLM, LM Studio and many providers offer. */
  final class OpenAi implements ModelApi {
    @Override
    public Reply chat(
        Profile p, Optional<String> key, String system, String user, JsonNode schema, int maxTokens)
        throws CapabilityException {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", p.model());
      body.put(
          "messages",
          List.of(
              Map.of("role", "system", "content", system),
              Map.of("role", "user", "content", user)));
      body.put("temperature", p.temperature());
      body.put("max_tokens", maxTokens);
      body.put("stream", false);
      if (schema != null) {
        body.put(
            "response_format",
            Map.of(
                "type",
                "json_schema",
                "json_schema",
                Map.of("name", "answer", "strict", true, "schema", schema)));
      }
      JsonNode answer = Http.post(path(p.url(), "/chat/completions"), body, key, p.timeoutMillis());
      JsonNode content = answer.path("choices").path(0).path("message").path("content");
      if (!content.isTextual()) {
        throw new CapabilityException("the server sent no answer (is the model right?)", false);
      }
      return new Reply(
          content.asText(),
          answer.path("usage").path("prompt_tokens").asLong(0),
          answer.path("usage").path("completion_tokens").asLong(0));
    }

    @Override
    public List<Double> embed(Profile p, Optional<String> key, String text)
        throws CapabilityException {
      JsonNode answer =
          Http.post(
              path(p.url(), "/embeddings"),
              Map.of("model", p.embedModel().orElse(p.model()), "input", text),
              key,
              p.timeoutMillis());
      return numbers(answer.path("data").path(0).path("embedding"));
    }
  }
}
