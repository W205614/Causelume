// Demo mode must be selected explicitly. API errors always reach the operator.
export const demo = new URLSearchParams(location.search).get("demo") === "1";
const fixture = demo ? await import("./mock.js") : null;
export async function request(path, body) {
  if (fixture) return fixture.request(path, body);
  const response = await fetch(
    "/api" + path,
    body === undefined
      ? {}
      : {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        },
  );
  const json = response.headers
    .get("content-type")
    ?.includes("application/json")
    ? await response.json()
    : null;
  if (!response.ok)
    throw Object.assign(
      Error(json?.message || "请求失败（HTTP " + response.status + "）"),
      { status: response.status },
    );
  if (json === null) throw Error("服务返回了无法识别的数据。");
  const cursor = response.headers.get("X-Next-Cursor");
  if (cursor && ["/users", "/services"].includes(path.split("?")[0])) {
    const base = path.split("?")[0];
    const rest = await request(base + "?afterId=" + encodeURIComponent(cursor));
    return json.concat(rest);
  }
  return json;
}
