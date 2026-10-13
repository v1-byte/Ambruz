/**
 * Ambruz backend — Cloudflare Worker + D1 (opsional)
 * Endpoint proyek milik pengguna. Bukan proxy scrape sewenang-wenang.
 */
const MAX_BODY = 64 * 1024;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": env.ALLOWED_ORIGIN || "*",
          "Access-Control-Allow-Methods": "GET,POST,PUT,DELETE,OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type, Authorization",
        },
      });
    }

    if (url.pathname === "/api/health") {
      return json({ ok: true, service: "ambruz", version: "0.1.0" });
    }

    // Tanpa D1 binding, endpoint proyek mengembalikan stub
    if (url.pathname === "/api/projects" && request.method === "GET") {
      return json({ projects: [], note: "Hubungkan D1 untuk persistensi." });
    }

    if (url.pathname === "/api/projects" && request.method === "POST") {
      const text = await request.text();
      if (text.length > MAX_BODY) return json({ error: "body_too_large" }, 413);
      let body;
      try {
        body = JSON.parse(text);
      } catch {
        return json({ error: "invalid_json" }, 400);
      }
      if (typeof body?.sourceUrl !== "string") {
        return json({ error: "sourceUrl_required" }, 400);
      }
      return json({
        id: crypto.randomUUID(),
        sourceUrl: body.sourceUrl,
        status: "created",
        note: "Stub — persist ke D1 di milestone backend.",
      }, 201);
    }

    return json({ error: "not_found" }, 404);
  },
};
