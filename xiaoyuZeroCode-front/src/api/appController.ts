// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 POST /app/add */
export async function addApp(
  body: API.AppAddRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseLong>("/app/add", {
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
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/admin/delete", {
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
  body: API.AppFeatureAuditRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/admin/feature/audit", {
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
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/admin/feature/cancel", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /app/admin/get */
export async function getAppById(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getAppByIdParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseApp>("/app/admin/get", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/admin/list/page */
export async function listAppByPage(
  body: API.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePageApp>("/app/admin/list/page", {
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
  body: API.AppUpdateRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/admin/update", {
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
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/delete", {
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
  body: API.DeleteBatchRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/deleteBatch", {
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
  body: API.AppDeployRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseString>("/app/deploy", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/feature/apply */
export async function applyFeatured(
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/feature/apply", {
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
  params: API.getAppCodeParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseAiCodeGenResultVO>("/app/get/code", {
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
  params: API.getAppVOByIdParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseAppVO>("/app/get/vo", {
    method: "GET",
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/list/featured/page/vo */
export async function listFeaturedAppVoByPage(
  body: API.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePageAppVO>("/app/list/featured/page/vo", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/list/page/vo */
export async function listMyAppVoByPage(
  body: API.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePageAppVO>("/app/list/page/vo", {
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
  body: API.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePageAppVO>("/app/list/public/page/vo", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/list/recycle/page/vo */
export async function listRecycledAppVoByPage(
  body: API.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePageAppVO>("/app/list/recycle/page/vo", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/physicalDelete */
export async function physicalDeleteApp(
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/physicalDelete", {
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
  body: API.DeleteBatchRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/physicalDeleteBatch", {
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
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/recover", {
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
  body: API.DeleteBatchRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/recoverBatch", {
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
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/unDeploy", {
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
  body: API.AppUpdateRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>("/app/update", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
