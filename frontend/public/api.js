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
      { status: response.status, code: json?.code },
    );
  if (json === null) throw Error("服务返回了无法识别的数据。");
  const cursor = response.headers.get("X-Next-Cursor");
  if (body === undefined && cursor && ["/users", "/services"].includes(path.split("?")[0])) {
    const [base, query] = path.split("?");
    const params = new URLSearchParams(query);
    params.set("afterId", cursor);
    const rest = await request(base + "?" + params);
    return json.concat(rest);
  }
  return json;
}

export async function exportTicket(id) {
  if (demo) throw Error("界面演示不支持导出，请登录业务控制台。");
  const response = await fetch("/api/tickets/" + id + "/export?format=markdown", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{}",
  });
  if (!response.ok) {
    const json = await response.json();
    throw Object.assign(Error(json.message || "导出失败（HTTP " + response.status + "）"), {
      status: response.status,
    });
  }
  return response.blob();
}
