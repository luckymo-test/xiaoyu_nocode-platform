// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /ai/chat */
export async function chat(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.chatParams,
  options?: { [key: string]: any }
) {
  return request<API.AiCodeGenResultVO>("/ai/chat", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/multi */
export async function chatMulti(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.chatMultiParams,
  options?: { [key: string]: any }
) {
  return request<API.AiCodeGenResultVO>("/ai/chat/multi", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/multi/stream */
export async function chatMultiStream(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.chatMultiStreamParams,
  options?: { [key: string]: any }
) {
  return request<API.SseEmitter>("/ai/chat/multi/stream", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/stream */
export async function chatStream(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.chatStreamParams,
  options?: { [key: string]: any }
) {
  return request<API.SseEmitter>("/ai/chat/stream", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/vue */
export async function chatVue(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.chatVueParams,
  options?: { [key: string]: any }
) {
  return request<API.AiCodeGenResultVO>("/ai/chat/vue", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/vue/stream */
export async function chatVueStream(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.chatVueStreamParams,
  options?: { [key: string]: any }
) {
  return request<API.SseEmitter>("/ai/chat/vue/stream", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /ai/stop */
export async function stopGenerate(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.stopGenerateParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/ai/stop", {
    method: "POST",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
