// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 POST /ai/manual-edit/apply */
export async function apply(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.applyParams,
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseEditItem>("/ai/manual-edit/apply", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    params: {
      ...params,
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /ai/manual-edit/clear */
export async function clear(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.clearParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseInteger>("/ai/manual-edit/clear", {
    method: "POST",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/manual-edit/list */
export async function list7(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.list7Params,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseListEditItem>(
    "/ai/manual-edit/list",
    {
      method: "GET",
      params: {
        ...params,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /ai/manual-edit/undo */
export async function undo(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.undoParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/ai/manual-edit/undo", {
    method: "POST",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
