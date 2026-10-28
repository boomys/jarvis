// J.A.R.V.I.S. - Cloudflare Worker
// /api/chat üzerinden AI beynini çalıştırır; /api/status durumu bildirir.
// API anahtarı SADECE sunucuda durur (env.OPENAI_API_KEY), istemciye asla gitmez.

const API_BASE = "https://freebuff.com/api/v1/paid";
const DEFAULT_MODEL = "gpt-4o-mini";
const MAX_MESSAGES = 12;

const SYSTEM_PROMPT = [
  "Sen J.A.R.V.I.S.'sin: Türkçe konuşan, kibar, zeki ve sadık bir kişisel asistansın.",
  "Kullanıcıya her zaman 'efendim' diye hitap edersin (ör. 'Efendim, ...').",
  "Cevapların sesli okunacağı için kısa olsun: en fazla 3 cümle, madde imi/emoji/kod bloğu yok.",
  "Telefonun kamerasını, uygulamalarını veya izinlerini doğrudan kontrol edemezsin;",
  "bunları uygulamanın kendi komut motoru yapar. Gerekirse kullanıcıya 'kamerayı aç' gibi bir komut yazmasını söyle.",
  "Bilmediğin bir şey varsa uydurma, kısaca söyle.",
].join(" ");

let cachedModel = null;
const hits = new Map();

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function rateLimited(ip) {
  const now = Date.now();
  const windowMs = 60 * 60 * 1000;
  const limit = 80;
  const rec = hits.get(ip);
  if (!rec || now - rec.start > windowMs) {
    hits.set(ip, { start: now, count: 1 });
    return false;
  }
  rec.count += 1;
  return rec.count > limit;
}

async function pickModel(key, env) {
  if (env.OPENAI_MODEL) return env.OPENAI_MODEL;
  if (cachedModel) return cachedModel;
  try {
    const res = await fetch(`${API_BASE}/v1/models`, {
      headers: { authorization: `Bearer ${key}` },
    });
    if (res.ok) {
      const data = await res.json();
      const list = Array.isArray(data?.data) ? data.data : [];
      const id = list.find((m) => m && typeof m.id === "string" && m.id)?.id;
      if (id) {
        cachedModel = id;
        return id;
      }
    }
  } catch (_) {
    /* model listesi alınamazsa varsayılana düşeriz */
  }
  return DEFAULT_MODEL;
}

async function handleChat(request, env) {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const key = env.OPENAI_API_KEY;
  if (!key) return json({ error: "no_key" }, 503);

  const ip = request.headers.get("cf-connecting-ip") || "anonymous";
  if (rateLimited(ip)) return json({ error: "rate_limited" }, 429);

  let payload;
  try {
    payload = await request.json();
  } catch (_) {
    return json({ error: "bad_json" }, 400);
  }

  const incoming = Array.isArray(payload?.messages) ? payload.messages : [];
  const messages = incoming
    .filter((m) => m && (m.role === "user" || m.role === "assistant") && typeof m.content === "string")
    .slice(-MAX_MESSAGES)
    .map((m) => ({ role: m.role, content: m.content.slice(0, 2000) }));

  if (!messages.length) return json({ error: "empty" }, 400);

  const model = await pickModel(key, env);

  let upstream;
  try {
    upstream = await fetch(`${API_BASE}/v1/chat/completions`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${key}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model,
        messages: [{ role: "system", content: SYSTEM_PROMPT }, ...messages],
        max_tokens: 400,
        temperature: 0.7,
      }),
    });
  } catch (err) {
    return json({ error: "upstream_unreachable", detail: String(err) }, 502);
  }

  if (!upstream.ok) {
    const text = await upstream.text().catch(() => "");
    return json({ error: "upstream_error", status: upstream.status, detail: text.slice(0, 400) }, 502);
  }

  const data = await upstream.json().catch(() => null);
  const reply = data?.choices?.[0]?.message?.content;
  if (!reply || typeof reply !== "string") return json({ error: "empty_reply" }, 502);

  return json({ reply: reply.trim(), model });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/api/chat") return handleChat(request, env);

    if (url.pathname === "/api/status") {
      return json({ ai: Boolean(env.OPENAI_API_KEY), service: "jarvis" });
    }

    return env.ASSETS.fetch(request);
  },
};
