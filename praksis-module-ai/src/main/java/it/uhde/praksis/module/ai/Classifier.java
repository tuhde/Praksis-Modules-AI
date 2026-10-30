// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.util.List;

/**
 * Which of some labels fits a text: the question to the model, and the check of the answer. What
 * the model says that is not on the list is not a label, however it got there.
 */
final class Classifier {
  private Classifier() {}

  /** The label (empty if none of the labels was chosen) and what the call cost. */
  record Verdict(String label, boolean matched, Runner.Done done) {}

  static Verdict classify(
      Runner runner,
      CapabilityContext context,
      String capability,
      Profile profile,
      String text,
      List<String> labels,
      String instructions)
      throws CapabilityException {
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
    Runner.Done done =
        runner.chat(context, capability, profile, null, instruction, text, schema, 64);
    String chosen = context.dryRun() ? "" : answer(done.reply().text());
    boolean matched = labels.contains(chosen);
    return new Verdict(matched ? chosen : "", matched, done);
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
