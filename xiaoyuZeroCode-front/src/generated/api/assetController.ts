// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /v1/assets */
export async function list5(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.list5Params,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseListAssetVO>("/v1/assets", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /v1/assets/${param0} */
export async function get1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.get1Params,
  options?: { [key: string]: any }
) {
  const { assetId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseAssetVO>(`/v1/assets/${param0}`, {
    method: "GET",
    params: { ...queryParams },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /v1/assets/upload */
export async function upload(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.uploadParams,
  body: {},
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseAssetVO>("/v1/assets/upload", {
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
