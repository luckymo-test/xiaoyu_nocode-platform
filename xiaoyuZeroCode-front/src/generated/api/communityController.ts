// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /v1/community/publications */
export async function listPublished(options?: { [key: string]: any }) {
  return request<GeneratedAPI.BaseResponseListAppPublicationVO>(
    "/v1/community/publications",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /v1/community/publications */
export async function create(
  body: GeneratedAPI.PublicationCreateRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseAppPublicationVO>(
    "/v1/community/publications",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      data: body,
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /v1/community/publications/${param0} */
export async function getPublished(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.getPublishedParams,
  options?: { [key: string]: any }
) {
  const { publicationId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseAppPublicationVO>(
    `/v1/community/publications/${param0}`,
    {
      method: "GET",
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /v1/community/publications/${param0}/review */
export async function review(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.reviewParams,
  body: GeneratedAPI.PublicationReviewRequest,
  options?: { [key: string]: any }
) {
  const { publicationId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseAppPublicationVO>(
    `/v1/community/publications/${param0}/review`,
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

/** 此处后端没有提供注释 POST /v1/community/publications/${param0}/takedown */
export async function takedown(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.takedownParams,
  options?: { [key: string]: any }
) {
  const { publicationId: param0, ...queryParams } = params;
  return request<GeneratedAPI.BaseResponseBoolean>(
    `/v1/community/publications/${param0}/takedown`,
    {
      method: "POST",
      params: {
        ...queryParams,
      },
      ...(options || {}),
    }
  );
}
