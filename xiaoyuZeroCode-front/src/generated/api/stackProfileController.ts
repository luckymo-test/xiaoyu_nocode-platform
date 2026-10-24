// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /v1/runtime-profiles */
export async function list3(options?: { [key: string]: any }) {
  return request<GeneratedAPI.BaseResponseListStackProfileVO>(
    "/v1/runtime-profiles",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /v1/stack-profiles */
export async function list4(options?: { [key: string]: any }) {
  return request<GeneratedAPI.BaseResponseListStackProfileVO>(
    "/v1/stack-profiles",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}
