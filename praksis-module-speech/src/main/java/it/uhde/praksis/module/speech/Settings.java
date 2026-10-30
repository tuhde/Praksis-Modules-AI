// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.speech;

import it.uhde.praksis.capability.CapabilityException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings of the module in {@code [module.speech]}: the Python with the vosk package, the
 * commands that give it audio, and one profile per language (a model directory).
 *
 * @param python the program that runs the recognizer; it needs the package {@code vosk}
 * @param recordCommand a command that writes what the microphone hears to its standard output as
 *     raw audio (16 kHz, mono, 16 bit little endian), e.g. {@code parec}
 * @param convertCommand a command that turns the audio file named by the one argument {@code
 *     {file}} into the same raw audio, e.g. {@code ffmpeg}
 * @param maxSeconds the longest that a call may listen
 */
record Settings(
    List<String> python,
    List<String> recordCommand,
    List<String> convertCommand,
    int maxSeconds,
    long maxFileBytes,
    Map<String, Profile> profiles,
    String defaultProfile) {

  /** A language: the directory of its Vosk model. */
  record Profile(String name, String language, Path model) {}

  static final List<String> RECORD =
      List.of("parec", "--format=s16le", "--rate=16000", "--channels=1");
  static final List<String> CONVERT =
      List.of(
          "ffmpeg",
          "-nostdin",
          "-v",
          "error",
          "-i",
          "{file}",
          "-f",
          "s16le",
          "-ar",
          "16000",
          "-ac",
          "1",
          "-");

  static Settings parse(Map<String, Object> settings) throws CapabilityException {
    List<String> python = List.of("python3");
    List<String> record = RECORD;
    List<String> convert = CONVERT;
    int max = 30;
    long maxFile = 200L << 20;
    String defaultName = null;
    Map<String, Profile> profiles = new LinkedHashMap<>();
    for (var e : settings.entrySet()) {
      switch (e.getKey()) {
        case "python" -> python = command(e.getValue(), "python");
        case "record_command" -> record = command(e.getValue(), "record_command");
        case "convert_command" -> {
          convert = command(e.getValue(), "convert_command");
          if (!convert.contains("{file}")) {
            throw bad("'convert_command' needs an argument {file}");
          }
        }
        case "max_seconds" -> {
          if (!(e.getValue() instanceof Number n) || n.intValue() < 1 || n.intValue() > 300) {
            throw bad("'max_seconds' must be a whole number from 1 to 300");
          }
          max = n.intValue();
        }
        case "max_file_mb" -> {
          if (!(e.getValue() instanceof Number n) || n.intValue() < 1 || n.intValue() > 4096) {
            throw bad("'max_file_mb' must be a whole number from 1 to 4096");
          }
          maxFile = n.longValue() << 20;
        }
        case "default" -> {
          if (!(e.getValue() instanceof String s) || s.isBlank()) {
            throw bad("'default' must be a text");
          }
          defaultName = s;
        }
        default -> {
          if (!(e.getValue() instanceof Map<?, ?> table)) {
            throw bad("unknown speech setting '" + e.getKey() + "'");
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
    return new Settings(python, record, convert, max, maxFile, Map.copyOf(profiles), defaultName);
  }

  private static Profile profile(String name, Map<?, ?> table) throws CapabilityException {
    if (!name.matches("[a-z][a-z0-9_-]{0,31}")) {
      throw bad("the profile name '" + name + "' must be lower case letters, digits, '_' or '-'");
    }
    String language = name;
    Path model = null;
    for (var e : table.entrySet()) {
      switch (String.valueOf(e.getKey())) {
        case "model" -> {
          if (!(e.getValue() instanceof String s) || s.isBlank()) {
            throw bad("profile '" + name + "': 'model' must be the directory of a Vosk model");
          }
          model = Path.of(s).toAbsolutePath().normalize();
        }
        case "language" -> {
          if (!(e.getValue() instanceof String s) || s.isBlank() || s.length() > 16) {
            throw bad("profile '" + name + "': 'language' must be a short text, like \"en\"");
          }
          language = s;
        }
        default -> throw bad("unknown setting '" + e.getKey() + "' in profile '" + name + "'");
      }
    }
    if (model == null) {
      throw bad("profile '" + name + "': 'model' is required");
    }
    if (!Files.isDirectory(model)) {
      throw bad("profile '" + name + "': the model directory " + model + " is not there");
    }
    return new Profile(name, language, model);
  }

  private static List<String> command(Object value, String key) throws CapabilityException {
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      throw bad("'" + key + "' must be a command: a list that starts with the program");
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (!(o instanceof String s) || s.isEmpty() || s.indexOf('\0') >= 0) {
        throw bad("'" + key + "' must hold only texts");
      }
      out.add(s);
    }
    return out;
  }

  Profile profile(String asked) throws CapabilityException {
    String name = asked != null && !asked.isBlank() ? asked : defaultProfile;
    if (name == null) {
      throw new CapabilityException(
          "no profile was asked for and 'default' names none; profiles: "
              + profiles.keySet().stream().sorted().toList(),
          false);
    }
    Profile p = profiles.get(name);
    if (p == null) {
      throw new CapabilityException(
          "no profile '" + name + "'; profiles: " + profiles.keySet().stream().sorted().toList(),
          false);
    }
    return p;
  }

  private static CapabilityException bad(String message) {
    return new CapabilityException(message, false);
  }
}
