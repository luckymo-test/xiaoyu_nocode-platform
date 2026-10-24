// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /v1/apps/${param0}/revisions */
export async function list6(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.list6Params,
  options?: { [key: string]: any }
) {
  const { appId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseListAppRevisionVO>(
    `/v1/apps/${param0}/revisions`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /v1/apps/${param0}/revisions/${param1} */
export async function get2(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.get2Params,
  options?: { [key: string]: any }
) {
  const { appId: param0, revisionId: param1, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseAppRevisionVO>(
    `/v1/apps/${param0}/revisions/${param1}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /v1/apps/${param0}/revisions/${param1}/publish */
export async function publish(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.publishParams,
  options?: { [key: string]: any }
) {
  const { appId: param0, revisionId: param1, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseAppRevisionVO>(
    `/v1/apps/${param0}/revisions/${param1}/publish`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /v1/apps/${param0}/revisions/${param1}/rollback */
export async function rollback(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.rollbackParams,
  options?: { [key: string]: any }
) {
  const { appId: param0, revisionId: param1, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseAppRevisionVO>(
    `/v1/apps/${param0}/revisions/${param1}/rollback`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /v1/apps/${param0}/revisions/compare */
export async function compare(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.compareParams,
  options?: { [key: string]: any }
) {
  const { appId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseAppRevisionDiffVO>(
    `/v1/apps/${param0}/revisions/compare`,
    {
      method: "GET",
      params: {
        ...queryParams,
      },
      ...(options || {}),
    }
  );
}
