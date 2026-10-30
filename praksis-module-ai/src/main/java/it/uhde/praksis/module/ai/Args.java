// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.CapabilityException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reading the arguments of a call. */
final class Args {
  private Args() {}

  static String text(Map<String, Object> args, String name, boolean required)
      throws CapabilityException {
    Object v = args.get(name);
    if (v == null) {
      if (required) {
        throw new CapabilityException("'" + name + "' is required", false);
      }
      return null;
    }
    if (!(v instanceof String s)) {
      throw new CapabilityException("'" + name + "' must be a text", false);
    }
    if (required && s.isBlank()) {
      throw new CapabilityException("'" + name + "' must not be empty", false);
    }
    return s;
  }

  static int number(Map<String, Object> args, String name, int fallback, int min, int max)
      throws CapabilityException {
    Object v = args.get(name);
    if (v == null) {
      return fallback;
    }
    if (!(v instanceof Number n) || n.longValue() < min || n.longValue() > max) {
      throw new CapabilityException(
          "'" + name + "' must be a whole number from " + min + " to " + max, false);
    }
    return n.intValue();
  }

  static boolean bool(Map<String, Object> args, String name) throws CapabilityException {
    Object v = args.get(name);
    if (v == null) {
      return false;
    }
    if (!(v instanceof Boolean b)) {
      throw new CapabilityException("'" + name + "' must be true or false", false);
    }
    return b;
  }

  /**
   * A list of short, different names or labels, of which there are at least {@code min} and at most
   * {@code max}; no control characters.
   */
  static List<String> names(
      Map<String, Object> args, String name, int min, int max, int length, String pattern)
      throws CapabilityException {
    if (!(args.get(name) instanceof List<?> list)) {
      throw new CapabilityException("'" + name + "' is required (a list of texts)", false);
    }
    if (list.size() < min || list.size() > max) {
      throw new CapabilityException(
          "'" + name + "' needs " + min + " to " + max + " entries, not " + list.size(), false);
    }
    Set<String> out = new LinkedHashSet<>();
    for (Object o : list) {
      if (!(o instanceof String s)
          || s.isBlank()
          || s.length() > length
          || s.matches(".*\\p{Cntrl}.*")
          || (pattern != null && !s.matches(pattern))) {
        throw new CapabilityException(
            "'"
                + name
                + "' holds an entry that is not allowed: "
                + (o instanceof String t && t.length() <= 40
                    ? "'" + t + "'"
                    : "(too long or not a text)"),
            false);
      }
      if (!out.add(s)) {
        throw new CapabilityException("'" + name + "' holds '" + s + "' twice", false);
      }
    }
    return new ArrayList<>(out);
  }
}
