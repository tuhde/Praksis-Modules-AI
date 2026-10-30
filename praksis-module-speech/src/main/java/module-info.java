// SPDX-License-Identifier: Apache-2.0
/** Speech to text with a local Vosk: {@code speech.listen} and {@code speech.transcribe}. */
module it.uhde.praksis.module.speech {
  requires it.uhde.praksis.capability;
  requires com.fasterxml.jackson.databind;

  provides it.uhde.praksis.capability.Capability with
      it.uhde.praksis.module.speech.SpeechListen,
      it.uhde.praksis.module.speech.SpeechTranscribe;
}
