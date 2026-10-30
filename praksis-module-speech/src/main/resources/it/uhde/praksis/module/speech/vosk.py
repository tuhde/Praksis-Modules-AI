# Reads raw audio (16 kHz, mono, 16 bit little endian) from stdin and prints what was said as one line
# of JSON: {"text": "..."}. Started by the speech module of Praksis as: python -c <this file> MODEL MODE.
# MODE "first" ends with the first sentence that is recognised, "all" reads until the audio ends.
# The first line it prints is {"ready": true}, after the model is loaded.
import json
import sys

from vosk import KaldiRecognizer, Model, SetLogLevel

SetLogLevel(-1)
model_dir, mode = sys.argv[1], sys.argv[2]
recognizer = KaldiRecognizer(Model(model_dir), 16000)
print(json.dumps({"ready": True}), flush=True)


def said(result):
    return json.loads(result).get("text", "")


parts = []
done = False
while True:
    data = sys.stdin.buffer.read(4000)
    if not data:
        break
    if recognizer.AcceptWaveform(data):
        text = said(recognizer.Result())
        if text:
            parts.append(text)
            if mode == "first":
                done = True
                break
if not done:
    text = said(recognizer.FinalResult())
    if text:
        parts.append(text)
print(json.dumps({"text": " ".join(parts)}), flush=True)
