// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ai.classify}: which of the given labels fits a text best. The server is told to answer
 * with one of the labels, and the answer is checked against the list anyway: what is not on it is
 * not a label, however the model got there, so a rule can branch on the result without trusting the
 * model.
 */
public final class AiClassify extends Base {
  @Override
  public String name() {
    return "ai.classify";
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    String text = Args.text(args, "text", true);
    List<String> labels = Args.names(args, "labels", 2, 50, 64, null);
    String instructions = Args.text(args, "instructions", false);
    String fallback = Args.text(args, "fallback", false);
    Runner runner = runner();
    Profile profile = runner.profile(Args.text(args, "profile", false));

    ObjectNode label = Http.JSON.createObjectNode().put("type", "string");
    ArrayNode choices = label.putArray("enum");
    labels.forEach(choices::add);
    ObjectNode schema = Http.JSON.createObjectNode().put("type", "object");
    schema.putObject("properties").set("label", label);
    schema.putArray("required").add("label");
    schema.put("additionalProperties", false);

    String instruction =
        "Decide which one of these labels fits the data best: "
            + String.join(", ", labels.stream().map(l -> "\"" + l + "\"").toList())
            + ". "
            + (instructions == null ? "" : instructions + " ")
            + "Answer with JSON of the form {\"label\": \"...\"} and nothing else.";
    Runner.Done done = runner.chat(context, name(), profile, null, instruction, text, schema, 64);

    String chosen = context.dryRun() ? "" : answer(done.reply().text());
    boolean matched = labels.contains(chosen);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("label", matched ? chosen : fallback == null ? "" : fallback);
    result.put("matched", matched);
    return with(result, done);
  }

  private static String answer(String text) {
    try {
      JsonNode n = Http.JSON.readTree(text.strip());
      return n != null && n.path("label").isTextual() ? n.get("label").asText() : "";
    } catch (IOException e) {
      return "";
    }
  }
}
