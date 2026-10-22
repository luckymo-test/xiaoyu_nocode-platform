package com.mofanyi.controller;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.service.AppAuthService;
import com.mofanyi.service.ManualEditService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 手动样式编辑接口：查看 / 追加 / 撤销 / 清空手动修改。
 * 仅应用创建者可用；支持 html / multi_file（vue 用构建产物预览，暂不支持）。
 */
@RestController
@RequestMapping("/ai/manual-edit")
public class ManualEditController {

    @Autowired
    private ManualEditService manualEditService;

    @Autowired
    private AppAuthService appAuthService;

    @Autowired
    private UserService userService;

    /**
     * 列出某应用全部手动修改
     */
    @GetMapping("/list")
    public BaseResponse<List<ManualEditService.EditItem>> list(@RequestParam Long appId,
                                                               HttpServletRequest request) {
        CodeGenTypeEnum type = checkOwner(appId, request);
        return ResultUtils.success(manualEditService.list(type, appId));
    }

    /**
     * 追加一条手动修改（body: {kind: style|text, selector, css?, text?}）
     */
    @PostMapping("/apply")
    public BaseResponse<ManualEditService.EditItem> apply(@RequestParam Long appId,
                                                          @RequestBody Map<String, String> body,
                                                          HttpServletRequest request) {
        CodeGenTypeEnum type = checkOwner(appId, request);
        String kind = body == null ? "style" : String.valueOf(body.getOrDefault("kind", "style"));
        String selector = body == null ? "" : StrUtil.maxLength(body.getOrDefault("selector", ""), 200);
        if ("text".equals(kind)) {
            String text = body == null ? "" : body.getOrDefault("text", "");
            return ResultUtils.success(manualEditService.applyText(type, appId, selector, text));
        }
        String css = body == null ? "" : body.getOrDefault("css", "");
        return ResultUtils.success(manualEditService.applyStyle(type, appId, selector, css));
    }

    /**
     * 撤销一条手动修改
     */
    @PostMapping("/undo")
    public BaseResponse<Boolean> undo(@RequestParam Long appId,
                                      @RequestParam String id,
                                      HttpServletRequest request) {
        CodeGenTypeEnum type = checkOwner(appId, request);
        return ResultUtils.success(manualEditService.undo(type, appId, id));
    }

    /**
     * 清空全部手动修改
     */
    @PostMapping("/clear")
    public BaseResponse<Integer> clear(@RequestParam Long appId, HttpServletRequest request) {
        CodeGenTypeEnum type = checkOwner(appId, request);
        return ResultUtils.success(manualEditService.clear(type, appId));
    }

    /**
     * 鉴权：登录 + 本人应用 + 非 vue，返回代码类型
     */
    private CodeGenTypeEnum checkOwner(Long appId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        App app = appAuthService.requireCanEdit(appId, loginUser);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "应用类型未知");
        ThrowUtils.throwIf(type == CodeGenTypeEnum.VUE,
                ErrorCode.PARAMS_ERROR, "Vue3 工程暂不支持手动样式编辑");
        return type;
    }
}
