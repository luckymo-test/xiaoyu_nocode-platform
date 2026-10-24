// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 POST /v1/apps/${param0}/generation-jobs */
export async function create1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.create1Params,
  body: GeneratedAPI.GenerationJobCreateRequest,
  options?: { [key: string]: any }
) {
  const { appId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseGenerationJobVO>(
    `/v1/apps/${param0}/generation-jobs`,
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

/** 此处后端没有提供注释 GET /v1/generation-jobs/${param0} */
export async function get(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.getParams,
  options?: { [key: string]: any }
) {
  const { jobId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseGenerationJobVO>(
    `/v1/generation-jobs/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /v1/generation-jobs/${param0}/cancel */
export async function cancel(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.cancelParams,
  options?: { [key: string]: any }
) {
  const { jobId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseGenerationJobVO>(
    `/v1/generation-jobs/${param0}/cancel`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /v1/generation-jobs/${param0}/events */
export async function events(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.eventsParams,
  options?: { [key: string]: any }
) {
  const { jobId: param0, ...queryParams } = params;
  return request<GeneratedAPI.SseEmitter>(
    `/v1/generation-jobs/${param0}/events`,
    {
      method: "GET",
      params: {
        ...queryParams,
      },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /v1/generation-jobs/${param0}/retry */
export async function retry(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.retryParams,
  options?: { [key: string]: any }
) {
  const { jobId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseGenerationJobVO>(
    `/v1/generation-jobs/${param0}/retry`,
    {
      method: "POST",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}
