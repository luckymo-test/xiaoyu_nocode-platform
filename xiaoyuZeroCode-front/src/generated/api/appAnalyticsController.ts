// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 POST /v1/analytics/events */
export async function record(
  body: GeneratedAPI.AnalyticsEventRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/v1/analytics/events", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /v1/apps/${param0}/analytics/daily */
export async function daily(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.dailyParams,
  options?: { [key: string]: any }
) {
  const { appId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseListAnalyticsDailyVO>(
    `/v1/apps/${param0}/analytics/daily`,
    {
      method: "GET",
      params: {
        ...queryParams,
      },
      ...(options || {}),
    }
  );
}
