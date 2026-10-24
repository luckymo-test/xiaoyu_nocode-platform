// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 POST /app/add */
export async function addApp(
  body: GeneratedAPI.AppAddRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseLong>("/app/add", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/admin/delete */
export async function deleteAppByAdmin(
  body: GeneratedAPI.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/admin/delete", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/admin/feature/audit */
export async function auditFeatured(
  body: GeneratedAPI.AppFeatureAuditRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/admin/feature/audit", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/admin/feature/cancel */
export async function cancelFeatured(
  body: GeneratedAPI.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>(
    "/app/admin/feature/cancel",
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

/** 此处后端没有提供注释 GET /app/admin/get */
export async function getAppById(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.getAppByIdParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseApp>("/app/admin/get", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/admin/list/page */
export async function listAppByPage(
  body: GeneratedAPI.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponsePageApp>("/app/admin/list/page", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/admin/update */
export async function updateAppByAdmin(
  body: GeneratedAPI.AppUpdateRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/admin/update", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/delete */
export async function deleteApp(
  body: GeneratedAPI.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/delete", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/deleteBatch */
export async function deleteAppBatch(
  body: GeneratedAPI.DeleteBatchRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/deleteBatch", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/deploy */
export async function deployApp(
  body: GeneratedAPI.AppDeployRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseString>("/app/deploy", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /app/deploy/stream */
export async function deployAppStream(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.deployAppStreamParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.SseEmitter>("/app/deploy/stream", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /app/download/code */
export async function downloadCode(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.downloadCodeParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.StreamingResponseBody>("/app/download/code", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/feature/apply */
export async function applyFeatured(
  body: GeneratedAPI.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/feature/apply", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /app/get/code */
export async function getAppCode(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.getAppCodeParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseAiCodeGenResultVO>("/app/get/code", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /app/get/vo */
export async function getAppVoById(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.getAppVOByIdParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseAppVO>("/app/get/vo", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /app/get/vue-preview */
export async function getVuePreview(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: GeneratedAPI.getVuePreviewParams,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseString>("/app/get/vue-preview", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/list/featured/page/vo */
export async function listFeaturedAppVoByPage(
  body: GeneratedAPI.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponsePageAppVO>(
    "/app/list/featured/page/vo",
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

/** 此处后端没有提供注释 POST /app/list/page/vo */
export async function listMyAppVoByPage(
  body: GeneratedAPI.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponsePageAppVO>("/app/list/page/vo", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/list/public/page/vo */
export async function listPublicAppVoByPage(
  body: GeneratedAPI.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponsePageAppVO>(
    "/app/list/public/page/vo",
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

/** 此处后端没有提供注释 POST /app/list/recycle/page/vo */
export async function listRecycledAppVoByPage(
  body: GeneratedAPI.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponsePageAppVO>(
    "/app/list/recycle/page/vo",
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

/** 此处后端没有提供注释 POST /app/physicalDelete */
export async function physicalDeleteApp(
  body: GeneratedAPI.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/physicalDelete", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/physicalDeleteBatch */
export async function physicalDeleteAppBatch(
  body: GeneratedAPI.DeleteBatchRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/physicalDeleteBatch", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/recover */
export async function recoverApp(
  body: GeneratedAPI.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/recover", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/recoverBatch */
export async function recoverAppBatch(
  body: GeneratedAPI.DeleteBatchRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/recoverBatch", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/unDeploy */
export async function unDeployApp(
  body: GeneratedAPI.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/unDeploy", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/update */
export async function updateApp(
  body: GeneratedAPI.AppUpdateRequest,
  options?: { [key: string]: any }
) {
  return request<GeneratedAPI.BaseResponseBoolean>("/app/update", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
