// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /v1/skills */
export async function list2(options?: { [key: string]: any }) {
  return request<GeneratedAPI.BaseResponseListSkillDefinitionVO>("/v1/skills", {
    method: "GET",
    ...(options || {}),
  });
}
