import * as admin from "firebase-admin";
import * as nodemailer from "nodemailer";
import { textBeltSafe } from "./logic/messages";

export interface Senders {
  push(tokens: string[], title: string, body: string, data?: Record<string, string>): Promise<{ ok: boolean; detail: string; invalidTokens: string[] }>;
  text(phone: string, body: string): Promise<{ ok: boolean; detail: string }>;
  email(to: string, subject: string, body: string, html?: string): Promise<{ ok: boolean; detail: string }>;
}

export interface Secrets { textbeltKey?: string; smtpUrl?: string; smtpFrom?: string }

/** Real senders. Secrets come from Firebase Secret Manager (or .secret.local in the emulator). */
export function realSenders(secrets: () => Secrets): Senders {
  return {
    async push(tokens, title, body, data) {
      if (!tokens.length) return { ok: false, detail: "no push tokens", invalidTokens: [] };
      // There is no FCM emulator; do not reach for real credentials while developing locally.
      if (process.env.FUNCTIONS_EMULATOR === "true") return { ok: false, detail: "push skipped (emulator)", invalidTokens: [] };
      const res = await admin.messaging().sendEachForMulticast({ tokens, notification: { title, body }, data, webpush: { fcmOptions: { link: "/" } } });
      const invalid: string[] = [];
      res.responses.forEach((r, i) => {
        const code = r.error?.code ?? "";
        if (code.includes("registration-token-not-registered") || code.includes("invalid-registration-token")) invalid.push(tokens[i]);
      });
      return { ok: res.successCount > 0, detail: `${res.successCount}/${tokens.length} delivered`, invalidTokens: invalid };
    },
    async text(phone, body) {
      const key = secrets().textbeltKey;
      if (!key) return { ok: false, detail: "TEXTBELT_KEY not configured" };
      const r = await fetch("https://textbelt.com/text", {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ phone, message: textBeltSafe(body), key }).toString(),
      });
      const j = (await r.json().catch(() => ({}))) as { success?: boolean; error?: string };
      return { ok: !!j.success, detail: j.success ? "sent" : (j.error ?? `HTTP ${r.status}`) };
    },
    async email(to, subject, body, html) {
      const { smtpUrl, smtpFrom } = secrets();
      if (!smtpUrl || !smtpFrom) return { ok: false, detail: "SMTP_URL / SMTP_FROM not configured" };
      try {
        await nodemailer.createTransport(smtpUrl).sendMail({ from: smtpFrom, to, subject, text: body, ...(html ? { html } : {}) });
        return { ok: true, detail: "sent" };
      } catch (e) { return { ok: false, detail: String(e) }; }
    },
  };
}
