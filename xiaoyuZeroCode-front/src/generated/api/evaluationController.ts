// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /v1/evaluation/prompts */
export async function prompts(options?: { [key: string]: any }) {
  return request<GeneratedAPI.BaseResponseMapStringObject>(
    "/v1/evaluation/prompts",
    {
      method: "GET",
      ...(options || {}),
    }
  );
}
