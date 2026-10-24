// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /v1/security/csrf */
export async function csrf(options?: { [key: string]: any }) {
  return request<GeneratedAPI.BaseResponseMapStringBoolean>(
    "/v1/security/csrf",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}
