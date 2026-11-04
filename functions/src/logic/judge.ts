/**
 * Judging something on the phone with Jev (System One API): which of the user's categories is it? Same prompt and
 * category descriptions as the Mac. Pure apart from [fetchFn].
 */
export const JEV_URL = "https://api.typesafe.ai/v1/systemone";
export const THRESHOLD = 0.6;

const DESCRIPTIONS: Record<string, string> = {
  CORE_WORK: "doing their job or project, such as building software, its tools, docs and tutorials",
  WORK_SUPPORT: "related to work without doing it, such as career, job hunting, professional networking, industry and tech commentary, work chat and meetings",
  ENTERTAINMENT: "fun videos, hobbies, sports, news, politics, social media",
  PERSONAL_ADMIN: "home, family, church, errands, personal email and calendar, banking, buying things they need",
  OTHER: "in between things, such as a new tab, a notification or system dialog, a launcher or search box",
};

export interface Seen { app?: string; title?: string; url?: string; screenText?: string; plan?: string }
export interface Cat { id: string; label: string; variable?: boolean; deleted?: boolean }

export function describe(s: Seen): string {
  const site = (() => { try { return s.url ? new URL(s.url).hostname.replace(/^www\./, "") : null; } catch { return null; } })();
  return [
    `App: ${s.app ?? "?"}`,
    site && `Site: ${site}`,
    s.title && `Title: ${s.title.slice(0, 200)}`,
    s.url && `Address: ${s.url.slice(0, 200)}`,
    s.plan && `Today's plan: ${s.plan.slice(0, 500)}`,
    s.screenText && `Text on screen (may be noisy):\n${s.screenText.slice(0, 1500)}`,
  ].filter(Boolean).join("\n");
}

/** Returns the chosen category id and its probability, or null when Jev isn't sure enough (or fails). */
export async function judge(seen: Seen, cats: Cat[], key: string, fetchFn: typeof fetch = fetch): Promise<{ category: string; probability: number } | null> {
  const usable = cats.filter((c) => !c.variable && !c.deleted);
  if (!usable.length) return null;
  const byLabel = new Map(usable.map((c) => [c.label, c]));
  const criteria = Object.fromEntries(usable.map((c) => [c.label, DESCRIPTIONS[c.id] ?? null]));
  const r = await fetchFn(JEV_URL, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
    body: JSON.stringify({ model: "jev-latest", state: describe(seen), questions: { c: { type: "choice", instructions: "The person has this open on their phone. Which category is it?", criteria } } }),
  });
  if (!r.ok) return null;
  const j = (await r.json()) as { answers?: { c?: { choice?: string; probabilities?: Record<string, number> } } };
  const a = j.answers?.c; if (!a) return null;
  const choice = a.choice ?? Object.entries(a.probabilities ?? {}).sort((x, y) => y[1] - x[1])[0]?.[0];
  const p = choice ? a.probabilities?.[choice] ?? 0 : 0;
  const c = choice ? byLabel.get(choice) : undefined;
  return c && p >= THRESHOLD ? { category: c.id, probability: p } : null;
}
