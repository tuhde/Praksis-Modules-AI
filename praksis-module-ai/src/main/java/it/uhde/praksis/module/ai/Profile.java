// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.CapabilityException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A way to reach a model, as the person set it up in {@code [module.ai.<name>]}.
 *
 * @param api how to talk to the server
 * @param url the address of the server (for {@link Api#OPENAI} including the path up to {@code
 *     /chat/completions}, usually {@code .../v1})
 * @param data where the text goes: {@link Data#LOCAL} stays on this machine, {@link Data#CLOUD} is
 *     anything else (another machine, a provider, or a local server that forwards to one)
 * @param credential the name of the credential binding that holds the key, if the server wants one
 * @param maxTokensPerDay the most tokens (in and out) a day may use, 0 for no limit
 */
record Profile(
    String name,
    Api api,
    URI url,
    String model,
    Optional<String> embedModel,
    Data data,
    Optional<String> credential,
    int timeoutMillis,
    int maxInputChars,
    int maxOutputTokens,
    long maxTokensPerDay,
    double temperature) {

  enum Api {
    OLLAMA,
    OPENAI
  }

  enum Data {
    LOCAL,
    CLOUD;

    String id() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private static final Set<String> KEYS =
      Set.of(
          "api",
          "url",
          "model",
          "embed_model",
          "data",
          "credential",
          "timeout_ms",
          "max_input_chars",
          "max_output_tokens",
          "max_tokens_per_day",
          "temperature");

  /** The profiles of the module settings, and what the module settings say besides. */
  record Settings(
      Map<String, Profile> profiles,
      String defaultProfile,
      int retentionDays,
      boolean logContent,
      boolean offline) {}

  static Settings parse(Map<String, Object> settings) throws CapabilityException {
    Map<String, Profile> profiles = new LinkedHashMap<>();
    String defaultName = null;
    int retention = 14;
    boolean logContent = true;
    boolean offline = false;
    for (var e : settings.entrySet()) {
      switch (e.getKey()) {
        case "default" -> defaultName = text(e.getValue(), "default");
        case "retention_days" -> retention = (int) number(e.getValue(), "retention_days", 1, 3650);
        case "log_content" -> {
          if (!(e.getValue() instanceof Boolean b)) {
            throw bad("'log_content' must be true or false");
          }
          logContent = b;
        }
        case "offline" -> {
          if (!(e.getValue() instanceof Boolean b)) {
            throw bad("'offline' must be true or false");
          }
          offline = b;
        }
        default -> {
          if (!(e.getValue() instanceof Map<?, ?> table)) {
            throw bad("unknown ai setting '" + e.getKey() + "'");
          }
          profiles.put(e.getKey(), profile(e.getKey(), table));
        }
      }
    }
    if (defaultName == null && profiles.size() == 1) {
      defaultName = profiles.keySet().iterator().next();
    }
    if (defaultName != null && !profiles.containsKey(defaultName)) {
      throw bad("'default' names the profile '" + defaultName + "', which is not set up");
    }
    return new Settings(Map.copyOf(profiles), defaultName, retention, logContent, offline);
  }

  private static Profile profile(String name, Map<?, ?> table) throws CapabilityException {
    if (!name.matches("[a-z][a-z0-9_-]{0,31}")) {
      throw bad("the profile name '" + name + "' must be lower case letters, digits, '_' or '-'");
    }
    Map<String, Object> m = new LinkedHashMap<>();
    for (var e : table.entrySet()) {
      String key = String.valueOf(e.getKey());
      if (!KEYS.contains(key)) {
        throw bad("unknown setting '" + key + "' in profile '" + name + "'");
      }
      m.put(key, e.getValue());
    }
    String where = "profile '" + name + "': ";
    Api api =
        switch (required(m, "api", where).toLowerCase(Locale.ROOT)) {
          case "ollama" -> Api.OLLAMA;
          case "openai" -> Api.OPENAI;
          default -> throw bad(where + "'api' must be \"ollama\" or \"openai\"");
        };
    URI url;
    try {
      url = new URI(required(m, "url", where));
    } catch (URISyntaxException e) {
      throw bad(where + "'url' is not an address: " + e.getMessage());
    }
    if (url.getHost() == null
        || !(Set.of("http", "https")
            .contains(String.valueOf(url.getScheme()).toLowerCase(Locale.ROOT)))
        || url.getUserInfo() != null) {
      throw bad(where + "'url' must be an http or https address with a host and no password");
    }
    Data data =
        switch (required(m, "data", where).toLowerCase(Locale.ROOT)) {
          case "local" -> Data.LOCAL;
          case "cloud" -> Data.CLOUD;
          default -> throw bad(where + "'data' must be \"local\" or \"cloud\"");
        };
    if (data == Data.LOCAL && !loopback(url.getHost())) {
      throw bad(
          where
              + "data = \"local\" needs a server on this machine (localhost or 127.0.0.1), but the"
              + " url names "
              + url.getHost()
              + "; write data = \"cloud\" for any other machine");
    }
    if (data == Data.CLOUD
        && !"https".equalsIgnoreCase(url.getScheme())
        && !loopback(url.getHost())) {
      throw bad(where + "a server on another machine must be reached with https");
    }
    return new Profile(
        name,
        api,
        url,
        required(m, "model", where),
        Optional.ofNullable(m.get("embed_model")).map(v -> String.valueOf(v)),
        data,
        Optional.ofNullable(m.get("credential")).map(v -> String.valueOf(v)),
        (int) numberOr(m, "timeout_ms", 120_000, 1_000, 600_000, where),
        (int) numberOr(m, "max_input_chars", 20_000, 100, 1_000_000, where),
        (int) numberOr(m, "max_output_tokens", 1_024, 1, 32_768, where),
        numberOr(m, "max_tokens_per_day", 0, 0, 1_000_000_000L, where),
        decimalOr(m, "temperature", 0.0, where));
  }

  static boolean loopback(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost")
        || h.equals("[::1]")
        || h.equals("::1")
        || h.matches("127(\\.\\d{1,3}){3}");
  }

  private static String required(Map<String, Object> m, String key, String where)
      throws CapabilityException {
    Object v = m.get(key);
    if (!(v instanceof String s) || s.isBlank()) {
      throw bad(where + "'" + key + "' is required (a text)");
    }
    return s;
  }

  private static String text(Object v, String key) throws CapabilityException {
    if (!(v instanceof String s) || s.isBlank()) {
      throw bad("'" + key + "' must be a text");
    }
    return s;
  }

  private static long number(Object v, String key, long min, long max) throws CapabilityException {
    if (!(v instanceof Number n) || n.longValue() < min || n.longValue() > max) {
      throw bad("'" + key + "' must be a whole number from " + min + " to " + max);
    }
    return n.longValue();
  }

  private static long numberOr(
      Map<String, Object> m, String key, long fallback, long min, long max, String where)
      throws CapabilityException {
    if (!m.containsKey(key)) {
      return fallback;
    }
    try {
      return number(m.get(key), key, min, max);
    } catch (CapabilityException e) {
      throw bad(where + e.getMessage());
    }
  }

  private static double decimalOr(Map<String, Object> m, String key, double fallback, String where)
      throws CapabilityException {
    if (!m.containsKey(key)) {
      return fallback;
    }
    if (!(m.get(key) instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 2) {
      throw bad(where + "'" + key + "' must be a number from 0 to 2");
    }
    return n.doubleValue();
  }

  private static CapabilityException bad(String message) {
    return new CapabilityException(message, false);
  }

  /** The names that can be asked for, for messages. */
  static List<String> names(Map<String, Profile> profiles) {
    return profiles.keySet().stream().sorted().toList();
  }
}
