/** Capital letters and digits, minus the look-alikes 0/O and 1/I so codes are easy to read and type. */
export const LINK_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

/** [random] returns n random bytes. 32 symbols divide 256 evenly, so `b % 32` is unbiased. */
export function makeLinkCode(random: (n: number) => Uint8Array, length = 5): string {
  return Array.from(random(length), (b) => LINK_ALPHABET[b % LINK_ALPHABET.length]).join("");
}

/** Uppercases and drops spaces/dashes; returns null unless the result is a well-formed code. */
export function normalizeLinkCode(raw: string, length = 5): string | null {
  const s = raw.toUpperCase().replace(/[\s-]/g, "");
  return new RegExp(`^[${LINK_ALPHABET}]{${length}}$`).test(s) ? s : null;
}
