import myAxios from '@/request'

/**
 * 查询渲染修复状态。
 *
 * 交付后的渲染修复是**异步**的（检测随生成返回，修复在后台跑），所以前端需要轮询这个接口。
 * 返回：ok | fixing | fixed | still_has_problems | skipped | error
 * 说明：这是手写文件（非 openapi2ts 生成），跑生成器时注意别被删。
 */
export async function getRenderFixStatus(params: { appId: string }) {
  const res = await myAxios.get('/ai/render-fix/status', { params })
  return res.data as { code: number; data: string | null; message: string }
}
