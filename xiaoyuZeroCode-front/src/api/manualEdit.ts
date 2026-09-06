import request from "@/request";

/** 单条手动样式修改 */
export interface ManualEditItem {
  id: string;
  selector: string;
  css: string;
}

/** 列出应用的手动修改 GET /ai/manual-edit/list */
export async function listManualEdits(params: { appId: number | string }, options?: { [key: string]: any }) {
  return request.get("/ai/manual-edit/list", {
    params: { ...params },
    ...(options || {}),
  });
}

/** 追加一条手动修改 POST /ai/manual-edit/apply */
export async function applyManualEdit(
  appId: number | string,
  body: { selector: string; css: string },
  options?: { [key: string]: any }
) {
  return request.post("/ai/manual-edit/apply", body, {
    params: { appId },
    headers: { "Content-Type": "application/json" },
    ...(options || {}),
  });
}

/** 撤销一条 POST /ai/manual-edit/undo */
export async function undoManualEdit(appId: number | string, id: string, options?: { [key: string]: any }) {
  return request.post("/ai/manual-edit/undo", null, {
    params: { appId, id },
    ...(options || {}),
  });
}

/** 清空全部 POST /ai/manual-edit/clear */
export async function clearManualEdits(appId: number | string, options?: { [key: string]: any }) {
  return request.post("/ai/manual-edit/clear", null, {
    params: { appId },
    ...(options || {}),
  });
}
