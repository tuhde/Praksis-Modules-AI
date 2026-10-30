// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ai.calls}: the log of the model calls, newest first. What was sent and what came back is
 * only in it when asked for with {@code content}, because it can be the text of a person's mail.
 */
public final class AiCalls extends Base {
  @Override
  public String name() {
    return "ai.calls";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    int limit = Args.number(args, "limit", 50, 1, 500);
    String profile = Args.text(args, "profile", false);
    boolean content = Args.bool(args, "content");
    List<Map<String, Object>> items = store().calls(limit, profile, content);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    return result;
  }
}
