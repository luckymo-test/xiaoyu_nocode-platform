// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /v1/templates */
export async function list1(options?: { [key: string]: any }) {
  return request<GeneratedAPI.BaseResponseListTemplateBundleVO>(
    "/v1/templates",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}
