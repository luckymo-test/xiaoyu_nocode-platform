// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /v1/workspaces/${param0}/members */
export async function list(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.listParams,
  options?: { [key: string]: any }
) {
  const { workspaceId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseListWorkspaceMemberVO>(
    `/v1/workspaces/${param0}/members`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /v1/workspaces/${param0}/members */
export async function add(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.addParams,
  body: GeneratedAPI.WorkspaceMemberAddRequest,
  options?: { [key: string]: any }
) {
  const { workspaceId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseWorkspaceMemberVO>(
    `/v1/workspaces/${param0}/members`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      params: { ...queryParams },
      data: body,
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 DELETE /v1/workspaces/${param0}/members/${param1} */
export async function remove(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.removeParams,
  options?: { [key: string]: any }
) {
  const { workspaceId: param0, userId: param1, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseBoolean>(
    `/v1/workspaces/${param0}/members/${param1}`,
    {
      method: "DELETE",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}
