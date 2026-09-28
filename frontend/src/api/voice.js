import axios from "axios";

const BASE = "/py-api";

export async function getVoices() {
  const res = await axios.get(`${BASE}/tts/voices`);
  return res.data; // { current, voices: [{id, name, gender, style}] }
}

export async function setVoice(voiceId) {
  const form = new FormData();
  form.append("voice_id", voiceId);
  const res = await axios.post(`${BASE}/tts/voice`, form);
  return res.data;
}

export async function synthesizeTTS(text, voice) {
  const form = new FormData();
  form.append("text", text);
  if (voice) form.append("voice", voice);
  const res = await axios.post(`${BASE}/tts`, form, { responseType: "blob" });
  return res.data;
}
