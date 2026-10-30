export interface Candidate { id: string; source: "ollama" | "huggingface"; sizeGb: number; rationale: string; sources: string[] }
export interface Recommendation { generatedAt: number; hardware: string; current: string; candidates: Candidate[]; dropped: string[] }

/** Parses the model's structured output, tolerating code fences. */
export function parseCandidates(text: string): Candidate[] {
  const m = text.match(/\[[\s\S]*\]|\{[\s\S]*\}/);
  if (!m) return [];
  let raw: unknown;
  try { raw = JSON.parse(m[0]); } catch { return []; }
  const arr = Array.isArray(raw) ? raw : Array.isArray((raw as { candidates?: unknown }).candidates) ? (raw as { candidates: unknown[] }).candidates : [];
  const out: Candidate[] = [];
  for (const c of arr as Record<string, unknown>[]) {
    const id = typeof c.id === "string" ? c.id.trim() : "";
    const source = c.source === "huggingface" ? "huggingface" : "ollama";
    if (!id) continue;
    out.push({ id, source, sizeGb: Number(c.sizeGb) || 0, rationale: String(c.rationale ?? ""), sources: Array.isArray(c.sources) ? c.sources.map(String) : [] });
  }
  return out;
}

/** Hallucinated IDs are dropped: every candidate must exist before it is published. */
export async function validateCandidates(cands: Candidate[], exists: (c: Candidate) => Promise<boolean>): Promise<{ valid: Candidate[]; dropped: string[] }> {
  const valid: Candidate[] = []; const dropped: string[] = [];
  for (const c of cands) (await exists(c).catch(() => false)) ? valid.push(c) : dropped.push(c.id);
  return { valid, dropped };
}

export function ollamaLibraryUrl(id: string): string {
  const [name, tag] = id.split(":");
  return tag ? `https://ollama.com/library/${name}:${tag}` : `https://ollama.com/library/${name}`;
}

export function buildPrompt(current: string, hardware: string): string {
  return `You advise on local multimodal (vision-language) models for on-device screen classification on a Mac (${hardware}).
The app currently uses "${current}" (4-bit). Using Google Search, find models released recently that are substantially better at this
(OCR-heavy screenshots, short structured JSON answers) AND fit the hardware at 4-bit. Respond with ONLY a JSON array of up to 3 objects:
[{"id": "<exact Ollama tag like qwen3-vl:4b OR Hugging Face repo like org/name>", "source": "ollama" | "huggingface", "sizeGb": number, "rationale": "<one sentence>", "sources": ["<url>", ...]}]
Only include IDs you found in search results. If nothing is substantially better, respond with [].`;
}
