// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /ai/chat */
export async function chat(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.chatParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.AiCodeGenResultVO>("/ai/chat", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      // reasoningLevel has a default value: standard
      reasoningLevel: "standard",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/multi */
export async function chatMulti(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.chatMultiParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.AiCodeGenResultVO>("/ai/chat/multi", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      // reasoningLevel has a default value: standard
      reasoningLevel: "standard",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/multi/stream */
export async function chatMultiStream(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.chatMultiStreamParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.SseEmitter>("/ai/chat/multi/stream", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      // reasoningLevel has a default value: standard
      reasoningLevel: "standard",

      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/stream */
export async function chatStream(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.chatStreamParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.SseEmitter>("/ai/chat/stream", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      // reasoningLevel has a default value: standard
      reasoningLevel: "standard",

      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/vue */
export async function chatVue(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.chatVueParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.AiCodeGenResultVO>("/ai/chat/vue", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      // reasoningLevel has a default value: standard
      reasoningLevel: "standard",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/chat/vue/stream */
export async function chatVueStream(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.chatVueStreamParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.SseEmitter>("/ai/chat/vue/stream", {
    method: "GET",
    params: {
      // message has a default value: 你好，请简单介绍一下你自己
      message: "你好，请简单介绍一下你自己",
      // reasoningLevel has a default value: standard
      reasoningLevel: "standard",

      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /ai/modify/stream */
export async function modifyStream(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.modifyStreamParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.SseEmitter>("/ai/modify/stream", {
    method: "GET",
    params: {
      // reasoningLevel has a default value: standard
      reasoningLevel: "standard",
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /ai/modify/undo */
export async function undoModify(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.undoModifyParams,
  body: Record<string, any>,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseAiCodeGenResultVO>(
    "/ai/modify/undo",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      params: {
        ...params,
      },
      data: body,
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /ai/render-fix/status */
export async function renderFixStatus(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.renderFixStatusParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseString>("/ai/render-fix/status", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /ai/stop */
export async function stopGenerate(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.stopGenerateParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/ai/stop", {
    method: "POST",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
